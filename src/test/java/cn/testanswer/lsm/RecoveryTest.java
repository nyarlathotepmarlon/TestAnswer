package cn.testanswer.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(FaultInjector.Point.class)
    void ioFailureAtEveryCommitBoundaryCanRecoverAndPoisonsCurrentHandle(FaultInjector.Point point) throws Exception {
        CrashProcess.seed(directory);
        try (LsmStore store = LsmStore.open(directory, LsmStoreTest.MANUAL, actual -> {
            if (actual == point) { throw new IOException("Injected failure at " + point); }
        })) {
            IOException error = assertThrows(IOException.class, () -> CrashProcess.exercise(store, point));
            assertTrue(error.getMessage().contains("Injected failure"));
            assertThrows(IOException.class, () -> store.put("unsafe", "must fail"));
            assertThrows(IOException.class, () -> store.get("stable"));
        }
        verifyRecovered(point);
    }

    @ParameterizedTest
    @EnumSource(FaultInjector.Point.class)
    void realAbruptJvmExitRecoversAtEveryCommitBoundary(FaultInjector.Point point) throws Exception {
        Process process = startChild(point.name());
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Child JVM timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(23, process.exitValue(), output);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        }
        verifyRecovered(point);
    }

    private void verifyRecovered(FaultInjector.Point point) throws Exception {
        Map<String, String> expected = point == FaultInjector.Point.AFTER_WAL_SYNC
                ? Map.of("stable", "稳定", "gone", "旧值", "new", "新值")
                : Map.of("stable", "稳定", "new", "新值");
        try (LsmStore store = LsmStore.open(directory, LsmStoreTest.MANUAL)) {
            assertEquals(expected, store.scan());
            store.compact();
            assertEquals(expected, store.scan());
            assertEquals(expected.size(), store.stats().sstableEntries());
            long sequence = store.stats().lastSequence();
            store.put("after-recovery", "可继续写入");
            assertEquals(sequence + 1, store.stats().lastSequence());
        }
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals("可继续写入", store.get("after-recovery").orElseThrow());
        }
    }

    @Test
    void crossProcessDirectoryLockRejectsAnotherJvm() throws Exception {
        try (LsmStore store = LsmStore.open(directory)) {
            store.put("locked", "value");
            Process process = startChild("probe-lock");
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(24, process.exitValue(), output);
            } finally {
                if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
            }
        }
    }

    private Process startChild(String mode) throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-cp", classpath,
                CrashProcess.class.getName(), directory.toString(), mode).redirectErrorStream(true).start();
    }

    @Test
    void everyPartialPrefixOfLastWalFrameIsDiscardedAndLaterAppendsStayReadable() throws Exception {
        byte[] partial = Frames.encode(RecordCodec.encode(new Entry(2, "unacknowledged", "尚未确认 🚀"))).array();
        for (int prefix = 1; prefix < partial.length; prefix++) {
            Path database = directory.resolve("prefix-" + prefix);
            long validLength;
            try (LsmStore store = LsmStore.open(database, LsmStoreTest.MANUAL)) {
                store.put("acknowledged", "不能丢失");
                validLength = store.stats().walBytes();
            }
            Files.write(database.resolve(Wal.FILE_NAME), Arrays.copyOf(partial, prefix), StandardOpenOption.APPEND);
            try (LsmStore store = LsmStore.open(database, LsmStoreTest.MANUAL)) {
                assertEquals("不能丢失", store.get("acknowledged").orElseThrow(), "prefix " + prefix);
                assertTrue(store.get("unacknowledged").isEmpty());
                assertEquals(validLength, store.stats().walBytes());
                store.put("later", "append works");
            }
            try (LsmStore store = LsmStore.open(database, LsmStoreTest.MANUAL)) {
                assertEquals("append works", store.get("later").orElseThrow());
            }
        }
    }

    @Test
    void completeWalRecordWithBadChecksumFailsWithoutTruncatingData() throws Exception {
        try (LsmStore store = LsmStore.open(directory)) { store.put("key", "value"); }
        Path wal = directory.resolve(Wal.FILE_NAME);
        byte[] bytes = Files.readAllBytes(wal);
        bytes[bytes.length - 1] ^= 1;
        Files.write(wal, bytes);
        assertThrows(CorruptStoreException.class, () -> LsmStore.open(directory));
        assertArrayEquals(bytes, Files.readAllBytes(wal));
    }

    @Test
    void corruptedWalLengthIsDetectedByComplementBeforeAllocation() throws Exception {
        try (LsmStore store = LsmStore.open(directory)) { store.put("key", "value"); }
        Path wal = directory.resolve(Wal.FILE_NAME);
        byte[] bytes = Files.readAllBytes(wal);
        bytes[0] ^= 0x40;
        Files.write(wal, bytes);
        assertThrows(CorruptStoreException.class, () -> LsmStore.open(directory));
    }

    @Test
    void invalidUtf8WithValidChecksumIsRejected() throws Exception {
        try (LsmStore store = LsmStore.open(directory)) { store.put("key", "value"); }
        byte[] payload = RecordCodec.encode(new Entry(1, "key", "value"));
        payload[RecordCodec.FIXED_BYTES] = (byte) 0x80;
        Files.write(directory.resolve(Wal.FILE_NAME), Frames.encode(payload).array());
        assertThrows(CorruptStoreException.class, () -> LsmStore.open(directory));
    }

    @Test
    void nonMonotonicWalSequenceIsRejected() throws Exception {
        try (LsmStore store = LsmStore.open(directory)) { store.put("key", "value"); }
        Files.write(directory.resolve(Wal.FILE_NAME),
                Frames.encode(RecordCodec.encode(new Entry(1, "other", "duplicate sequence"))).array(),
                StandardOpenOption.APPEND);
        assertThrows(CorruptStoreException.class, () -> LsmStore.open(directory));
    }

    @Test
    void oldWalCannotResurrectAKeyAfterTombstoneWasCompactedAway() throws Exception {
        byte[] staleWal;
        try (LsmStore store = LsmStore.open(directory, LsmStoreTest.MANUAL)) {
            store.put("gone", "must not return");
            staleWal = Files.readAllBytes(directory.resolve(Wal.FILE_NAME));
            store.flush();
            store.delete("gone");
            store.compact();
            assertEquals(0, store.stats().sstableCount());
        }
        Files.write(directory.resolve(Wal.FILE_NAME), staleWal);
        try (LsmStore store = LsmStore.open(directory, LsmStoreTest.MANUAL)) {
            assertTrue(store.get("gone").isEmpty());
            assertEquals(2, store.stats().lastSequence());
            store.put("next", "value");
            assertEquals(3, store.stats().lastSequence());
        }
    }

    @Test
    void corruptedManifestAndUnsupportedVersionAreRejected() throws Exception {
        try (LsmStore ignored = LsmStore.open(directory)) { }
        Path manifest = directory.resolve(Manifest.FILE_NAME);
        byte[] original = Files.readAllBytes(manifest);
        byte[] badChecksum = original.clone();
        badChecksum[badChecksum.length - 1] ^= 1;
        Files.write(manifest, badChecksum);
        assertThrows(CorruptStoreException.class, () -> LsmStore.open(directory));
        byte[] payload = Arrays.copyOfRange(original, Frames.HEADER_BYTES, original.length);
        ByteBuffer.wrap(payload).putInt(4, 999);
        Files.write(manifest, Frames.encode(payload).array());
        assertThrows(CorruptStoreException.class, () -> LsmStore.open(directory));
    }

    @Test
    void missingManifestDoesNotSilentlyInitializeOverExistingData() throws Exception {
        try (LsmStore store = LsmStore.open(directory)) { store.put("key", "value"); }
        Files.delete(directory.resolve(Manifest.FILE_NAME));
        assertThrows(CorruptStoreException.class, () -> LsmStore.open(directory));
        assertFalse(Files.exists(directory.resolve(Manifest.FILE_NAME)));
    }

    @Test
    void missingCommittedSstableFailsInsteadOfServingIncompleteData() throws Exception {
        try (LsmStore store = LsmStore.open(directory)) { store.put("key", "value"); store.flush(); }
        Manifest manifest = Manifest.load(directory);
        Files.delete(directory.resolve(manifest.tables().get(0)));
        assertThrows(CorruptStoreException.class, () -> LsmStore.open(directory));
    }

    @Test
    void corruptedSstableRecordOrMetadataFailsAtOpen() throws Exception {
        try (LsmStore store = LsmStore.open(directory)) { store.put("key", "value"); store.flush(); }
        Path table = directory.resolve(Manifest.load(directory).tables().get(0));
        byte[] original = Files.readAllBytes(table);
        byte[] corruptRecord = original.clone();
        corruptRecord[SSTable.HEADER_BYTES + Frames.HEADER_BYTES + RecordCodec.FIXED_BYTES] ^= 1;
        Files.write(table, corruptRecord);
        assertThrows(CorruptStoreException.class, () -> LsmStore.open(directory));
        byte[] corruptMetadata = original.clone();
        corruptMetadata[corruptMetadata.length - 1] ^= 1;
        Files.write(table, corruptMetadata);
        assertThrows(CorruptStoreException.class, () -> LsmStore.open(directory));
    }

    @Test
    void orphanTablesAndTemporaryFilesAreRemovedOnlyAfterSuccessfulRecovery() throws Exception {
        try (LsmStore store = LsmStore.open(directory)) { store.put("committed", "yes"); }
        Path orphan = directory.resolve(DiskIO.newTableName());
        Path tempTable = directory.resolve(DiskIO.newTableName() + ".tmp");
        Path tempManifest = directory.resolve("manifest-00000000-0000-0000-0000-000000000000.tmp");
        Files.writeString(orphan, "uncommitted");
        Files.writeString(tempTable, "partial");
        Files.writeString(tempManifest, "partial");
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals("yes", store.get("committed").orElseThrow());
            assertFalse(Files.exists(orphan));
            assertFalse(Files.exists(tempTable));
            assertFalse(Files.exists(tempManifest));
        }
    }
}

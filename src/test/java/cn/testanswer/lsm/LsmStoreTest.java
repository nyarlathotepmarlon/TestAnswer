package cn.testanswer.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LsmStoreTest {
    static final StoreOptions MANUAL = new StoreOptions(16 * 1024 * 1024, 32 * 1024 * 1024, 4, 0);
    @TempDir Path directory;

    @Test
    void putGetOverwriteDeleteAndRestartWithUnicodePath() throws Exception {
        Path database = directory.resolve("中文 目录");
        try (LsmStore store = LsmStore.open(database, MANUAL)) {
            assertTrue(store.get("missing").isEmpty());
            store.put("姓名", "张三 🚀");
            store.put("姓名", "李四\n带换行");
            store.put("empty", "");
            store.put("gone", "old");
            store.delete("gone");
            store.delete("absent");
            assertEquals("李四\n带换行", store.get("姓名").orElseThrow());
            assertEquals("", store.get("empty").orElseThrow());
            assertTrue(store.get("gone").isEmpty());
            assertEquals(0, store.stats().sstableCount());
        }
        try (LsmStore store = LsmStore.open(database, MANUAL)) {
            assertEquals(Map.of("姓名", "李四\n带换行", "empty", ""), store.scan());
            assertEquals(6, store.stats().lastSequence());
        }
    }

    @Test
    void flushPersistsSortedRecordsSparseIndexAndBloomFilter() throws Exception {
        try (LsmStore store = LsmStore.open(directory, MANUAL)) {
            for (int i = 199; i >= 0; i--) {
                store.put("key:%03d".formatted(i), "值:" + i);
            }
            store.flush();
            assertEquals(1, store.stats().sstableCount());
            assertEquals(0, store.stats().walBytes());
            assertEquals(0, store.stats().memTableBytes());
        }
        // 已存在表使用自己的索引间隔，不依赖本次启动配置。
        try (LsmStore store = LsmStore.open(directory, new StoreOptions(4096, 8192, 17, 0))) {
            for (int i = 0; i < 200; i++) {
                assertEquals("值:" + i, store.get("key:%03d".formatted(i)).orElseThrow());
            }
            assertTrue(store.get("key:099x").isEmpty());
            assertTrue(store.get("aaa").isEmpty());
            assertTrue(store.get("zzz").isEmpty());
            assertEquals(List.of("key:051", "key:052", "key:053"),
                    new ArrayList<>(store.scan("key:051", "key:065", 3).keySet()));
        }
    }

    @Test
    void memTableThresholdAutomaticallyFlushes() throws Exception {
        try (LsmStore store = LsmStore.open(directory, new StoreOptions(1, 10000, 2, 0))) {
            store.put("a", "1");
            store.put("b", "2");
            assertEquals(2, store.stats().sstableCount());
            assertEquals(0, store.stats().memTableEntries());
            assertEquals(Map.of("a", "1", "b", "2"), store.scan());
        }
    }

    @Test
    void walThresholdBoundsRepeatedOverwritesOfOneHotKey() throws Exception {
        try (LsmStore store = LsmStore.open(directory, new StoreOptions(100000, 120, 2, 0))) {
            for (int i = 0; i < 20; i++) { store.put("hot", "v" + i); }
            assertTrue(store.stats().sstableCount() > 0);
            assertTrue(store.stats().walBytes() < 120);
            assertEquals("v19", store.get("hot").orElseThrow());
        }
    }

    @Test
    void rangeScanMergesMemoryAndTablesWithHalfOpenBoundsAndLimit() throws Exception {
        try (LsmStore store = LsmStore.open(directory, MANUAL)) {
            store.put("a", "old-a"); store.put("b", "old-b"); store.put("c", "old-c");
            store.flush();
            store.put("a", "new-a"); store.delete("b"); store.put("d", "new-d");
            store.flush();
            store.put("c", "new-c"); store.put("e", "new-e");
            assertEquals(Map.of("a", "new-a", "c", "new-c", "d", "new-d", "e", "new-e"), store.scan());
            assertEquals(Map.of("c", "new-c", "d", "new-d"), store.scan("b", "e", 10));
            assertEquals(Map.of("c", "new-c"), store.scan("b", null, 1));
            assertEquals(Map.of("a", "new-a"), store.scan(null, "b", 10));
            assertTrue(store.scan("c", "c", 10).isEmpty());
            assertTrue(store.scan("z", null, 10).isEmpty());
            assertTrue(store.scan(null, "", 10).isEmpty());
        }
    }

    @Test
    void scanSnapshotIsImmutableAndUnaffectedByLaterWrites() throws Exception {
        try (LsmStore store = LsmStore.open(directory, MANUAL)) {
            store.put("key", "before");
            var snapshot = store.scan();
            store.put("key", "after");
            assertEquals(Map.of("key", "before"), snapshot);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.put("bad", "value"));
        }
    }

    @Test
    void compactionReclaimsDeletedAndOverwrittenRecordsAndOldFiles() throws Exception {
        try (LsmStore store = LsmStore.open(directory, MANUAL)) {
            store.put("a", "old"); store.put("b", "deleted"); store.flush();
            store.put("a", "latest"); store.delete("b"); store.put("c", "third"); store.flush();
            long beforeBytes = store.stats().sstableBytes();
            assertEquals(5, store.stats().sstableEntries());
            store.compact();
            assertEquals(1, store.stats().sstableCount());
            assertEquals(2, store.stats().sstableEntries());
            assertTrue(store.stats().sstableBytes() < beforeBytes);
            assertEquals(Map.of("a", "latest", "c", "third"), store.scan());
            try (var files = Files.list(directory)) {
                assertEquals(1, files.filter(path -> path.toString().endsWith(".sst")).count());
            }
        }
        try (LsmStore store = LsmStore.open(directory, MANUAL)) {
            assertEquals("latest", store.get("a").orElseThrow());
            assertTrue(store.get("b").isEmpty());
            assertEquals(Map.of("a", "latest", "c", "third"), store.scan("a", "d", 100));
        }
    }

    @Test
    void allDeletedCompactsToNoTablesWithoutLosingSequenceCheckpoint() throws Exception {
        try (LsmStore store = LsmStore.open(directory, MANUAL)) {
            store.put("a", "1"); store.flush(); store.delete("a");
            store.compact();
            assertEquals(0, store.stats().sstableCount());
            assertEquals(2, store.stats().checkpoint());
            assertTrue(store.scan().isEmpty());
        }
        try (LsmStore store = LsmStore.open(directory, MANUAL)) {
            assertTrue(store.get("a").isEmpty());
            store.put("a", "new");
            assertEquals(3, store.stats().lastSequence());
        }
    }

    @Test
    void automaticCompactionKeepsTableCountBelowConfiguredThreshold() throws Exception {
        try (LsmStore store = LsmStore.open(directory, new StoreOptions(1, 10000, 2, 3))) {
            for (int i = 0; i < 15; i++) {
                store.put("key", Integer.toString(i));
                assertTrue(store.stats().sstableCount() < 3);
            }
            assertEquals("14", store.get("key").orElseThrow());
        }
    }

    @Test
    void concurrentWritersDoNotLoseUpdatesAndRemainRecoverable() throws Exception {
        try (LsmStore store = LsmStore.open(directory, new StoreOptions(1024, 4096, 4, 4))) {
            var executor = Executors.newFixedThreadPool(4);
            try {
                List<Callable<Void>> jobs = new ArrayList<>();
                for (int worker = 0; worker < 4; worker++) {
                    int id = worker;
                    jobs.add(() -> {
                        for (int i = 0; i < 30; i++) {
                            String key = "worker-" + id + ":" + i;
                            store.put(key, "value-" + i);
                            store.put("hot", id + ":" + i);
                            assertEquals("value-" + i, store.get(key).orElseThrow());
                        }
                        return null;
                    });
                }
                for (var result : executor.invokeAll(jobs, 30, TimeUnit.SECONDS)) { result.get(); }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
            assertEquals(121, store.scan().size());
            assertEquals(240, store.stats().lastSequence());
        }
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(121, store.scan().size());
            assertEquals(240, store.stats().lastSequence());
        }
    }

    @Test
    void exclusiveDirectoryLockIsReleasedOnClose() throws Exception {
        try (LsmStore store = LsmStore.open(directory)) {
            IOException error = assertThrows(IOException.class, () -> LsmStore.open(directory));
            assertTrue(error.getMessage().contains("already open"));
            store.put("key", "value");
        }
        try (LsmStore reopened = LsmStore.open(directory)) {
            assertEquals("value", reopened.get("key").orElseThrow());
        }
    }

    @Test
    void invalidInputsAreRejectedBeforeWalWriteAndDoNotPoisonStore() throws Exception {
        try (LsmStore store = LsmStore.open(directory, MANUAL)) {
            assertThrows(IllegalArgumentException.class, () -> store.put("", "value"));
            assertThrows(IllegalArgumentException.class, () -> store.put(null, "value"));
            assertThrows(IllegalArgumentException.class, () -> store.put("key", null));
            assertThrows(IllegalArgumentException.class, () -> store.put("中".repeat(22000), "value"));
            assertThrows(IllegalArgumentException.class, () -> store.put("key", "x".repeat(RecordCodec.MAX_VALUE_BYTES + 1)));
            assertThrows(IllegalArgumentException.class, () -> store.put("key", "\ud800"));
            assertThrows(IllegalArgumentException.class, () -> store.scan("z", "a", 1));
            assertThrows(IllegalArgumentException.class, () -> store.scan(null, null, 0));
            assertEquals(0, store.stats().lastSequence());
            assertEquals(0, store.stats().walBytes());
            store.put("valid", "works");
            assertEquals("works", store.get("valid").orElseThrow());
        }
    }

    @Test
    void optionsRejectInvalidThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new StoreOptions(0, 100, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new StoreOptions(100, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new StoreOptions(100, 100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new StoreOptions(100, 100, 1, 1));
    }

    @Test
    void closeIsIdempotentAndSubsequentOperationsFail() throws Exception {
        LsmStore store = LsmStore.open(directory);
        store.close();
        store.close();
        assertThrows(IOException.class, () -> store.get("key"));
        assertThrows(IOException.class, () -> store.put("key", "value"));
        assertThrows(IOException.class, store::compact);
    }

    @Test
    void emptyFlushAndCompactionAreSafe() throws Exception {
        try (LsmStore store = LsmStore.open(directory)) {
            store.flush(); store.compact();
            assertEquals(0, store.stats().sstableCount());
            assertTrue(store.scan().isEmpty());
        }
    }

    @Test
    void maintenanceDoesNotDeleteUnrelatedFiles() throws Exception {
        Files.writeString(directory.resolve("notes.tmp"), "保留这个文件");
        Files.writeString(directory.resolve("unknown.sst"), "not a managed file");
        try (LsmStore store = LsmStore.open(directory)) {
            store.put("x", "y"); store.compact();
        }
        assertEquals("保留这个文件", Files.readString(directory.resolve("notes.tmp")));
        assertTrue(Files.exists(directory.resolve("unknown.sst")));
    }

    @Test
    void randomizedOperationsMatchTreeMapAcrossFlushCompactionAndRestart() throws Exception {
        TreeMap<String, String> reference = new TreeMap<>();
        Random random = new Random(20260929);
        StoreOptions options = new StoreOptions(256, 1024, 3, 4);
        LsmStore store = LsmStore.open(directory, options);
        try {
            for (int step = 0; step < 400; step++) {
                String key = "key:%02d".formatted(random.nextInt(30));
                switch (random.nextInt(10)) {
                    case 0, 1 -> { store.delete(key); reference.remove(key); }
                    case 2 -> store.flush();
                    case 3 -> store.compact();
                    case 4 -> { store.close(); store = LsmStore.open(directory, options); }
                    default -> {
                        String value = "值-" + step;
                        store.put(key, value); reference.put(key, value);
                    }
                }
                assertEquals(reference.get(key), store.get(key).orElse(null), "get at step " + step);
                if (step % 11 == 0) {
                    assertEquals(reference, store.scan(), "scan at step " + step);
                    assertEquals(reference.subMap("key:05", true, "key:20", false),
                            store.scan("key:05", "key:20", 100));
                }
            }
            store.compact();
            assertEquals(reference, store.scan());
        } finally {
            store.close();
        }
    }
}

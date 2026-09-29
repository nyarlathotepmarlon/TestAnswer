package cn.testanswer.lsm;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 单进程、多线程安全的简化 LSM-Tree。所有公开操作串行化，返回的 scan 是稳定快照。
 * 每次成功写入均已 force WAL；close 不隐式 flush，可直接通过 WAL 恢复。
 */
public final class LsmStore implements AutoCloseable {
    private final Path directory;
    private final StoreOptions options;
    private final FaultInjector faults;
    private final NavigableMap<String, Entry> memTable = new TreeMap<>();
    private final List<SSTable> tables = new ArrayList<>(); // 最旧 -> 最新
    private FileChannel lockChannel;
    private FileLock directoryLock;
    private Wal wal;
    private Manifest manifest;
    private long memBytes;
    private long lastSequence;
    private boolean closed;
    private IOException failure;

    private LsmStore(Path directory, StoreOptions options, FaultInjector faults) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.options = Objects.requireNonNull(options);
        this.faults = Objects.requireNonNull(faults);
    }

    public static LsmStore open(Path directory) throws IOException {
        return open(directory, StoreOptions.DEFAULT);
    }

    public static LsmStore open(Path directory, StoreOptions options) throws IOException {
        return open(directory, options, FaultInjector.NONE);
    }

    static LsmStore open(Path directory, StoreOptions options, FaultInjector faults) throws IOException {
        LsmStore store = new LsmStore(directory, options, faults);
        try {
            store.initialize();
            return store;
        } catch (IOException | RuntimeException e) {
            try {
                store.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    private void initialize() throws IOException {
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("LOCK"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            directoryLock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            throw new IOException("Database is already open: " + directory, e);
        }
        if (directoryLock == null) {
            throw new IOException("Database is already open in another process: " + directory);
        }
        if (!Files.exists(directory.resolve(Manifest.FILE_NAME))) {
            boolean hasTables;
            try (var files = Files.list(directory)) {
                hasTables = files.anyMatch(path -> DiskIO.isTableName(path.getFileName().toString()));
            }
            Path walPath = directory.resolve(Wal.FILE_NAME);
            if (hasTables || (Files.exists(walPath) && Files.size(walPath) > 0)) {
                throw new CorruptStoreException("MANIFEST is missing from an existing database");
            }
            new Manifest(0, List.of()).save(directory);
        }
        manifest = Manifest.load(directory);
        for (String name : manifest.tables()) {
            Path path = directory.resolve(name);
            if (!Files.isRegularFile(path)) {
                throw new CorruptStoreException("Committed SSTable is missing: " + name);
            }
            SSTable table = SSTable.open(path);
            if (table.maxSequence() > manifest.checkpoint()) {
                throw new CorruptStoreException("SSTable sequence exceeds MANIFEST checkpoint");
            }
            tables.add(table);
        }
        wal = new Wal(directory);
        lastSequence = wal.recover(manifest.checkpoint(), this::applyToMemTable);
        cleanupOrphans();
    }

    /** 空值应传 ""；null 被拒绝，删除请用 delete。 */
    public synchronized void put(String key, String value) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("Value must not be null; use delete(key)");
        }
        write(key, value);
    }

    /** 幂等的逻辑删除，即使 key 不存在也写入 tombstone。 */
    public synchronized void delete(String key) throws IOException {
        write(key, null);
    }

    private void write(String key, String value) throws IOException {
        ensureOpen();
        if (lastSequence == Long.MAX_VALUE) {
            throw new IOException("Sequence space exhausted");
        }
        Entry entry = new Entry(lastSequence + 1, key, value);
        byte[] encoded = RecordCodec.encode(entry); // 参数校验在写 WAL 之前完成。
        try {
            wal.append(encoded);
            faults.check(FaultInjector.Point.AFTER_WAL_SYNC);
            lastSequence = entry.sequence();
            applyToMemTable(entry);
            if (memBytes >= options.memTableBytes() || wal.size() >= options.walBytes()) {
                flushInternal();
                if (options.autoCompactTables() > 0 && tables.size() >= options.autoCompactTables()) {
                    compactInternal();
                }
            }
        } catch (IOException e) {
            failure = e;
            throw e;
        }
    }

    private void applyToMemTable(Entry entry) {
        Entry previous = memTable.put(entry.key(), entry);
        memBytes += entry.encodedSize() - (previous == null ? 0 : previous.encodedSize());
    }

    public synchronized Optional<String> get(String key) throws IOException {
        ensureOpen();
        RecordCodec.validateKey(key);
        Entry entry = memTable.get(key);
        if (entry != null) {
            return Optional.ofNullable(entry.value());
        }
        for (int i = tables.size() - 1; i >= 0; i--) {
            entry = tables.get(i).get(key);
            if (entry != null) {
                return Optional.ofNullable(entry.value());
            }
        }
        return Optional.empty();
    }

    /** Java String.compareTo 顺序，左闭右开；null 边界表示无界，limit 必须大于 0。 */
    public synchronized NavigableMap<String, String> scan(String fromInclusive, String toExclusive, int limit)
            throws IOException {
        ensureOpen();
        if (limit < 1) {
            throw new IllegalArgumentException("Scan limit must be positive");
        }
        if (fromInclusive != null) { Utf8.encode(fromInclusive); }
        if (toExclusive != null) { Utf8.encode(toExclusive); }
        if (fromInclusive != null && toExclusive != null && fromInclusive.compareTo(toExclusive) > 0) {
            throw new IllegalArgumentException("Scan start must not exceed end");
        }
        NavigableMap<String, Entry> memory = memTable;
        if (fromInclusive != null) { memory = memory.tailMap(fromInclusive, true); }
        if (toExclusive != null) { memory = memory.headMap(toExclusive, false); }
        List<EntryStream> sources = tableStreams(fromInclusive, toExclusive);
        sources.add(EntryStream.of(memory.values()));
        NavigableMap<String, String> result = new TreeMap<>();
        try (EntryStream merged = new MergeStream(sources, true)) {
            Entry entry;
            while (result.size() < limit && (entry = merged.next()) != null) {
                result.put(entry.key(), entry.value());
            }
        }
        return Collections.unmodifiableNavigableMap(result);
    }

    public synchronized NavigableMap<String, String> scan() throws IOException {
        return scan(null, null, Integer.MAX_VALUE);
    }

    public synchronized void flush() throws IOException {
        ensureOpen();
        try {
            flushInternal();
        } catch (IOException e) {
            failure = e;
            throw e;
        }
    }

    private void flushInternal() throws IOException {
        if (memTable.isEmpty()) {
            return;
        }
        SSTable table;
        try (EntryStream source = EntryStream.of(memTable.values())) {
            table = SSTable.write(directory, source, memTable.size(), options.indexStride());
        }
        faults.check(FaultInjector.Point.AFTER_SSTABLE_SYNC);
        List<String> names = new ArrayList<>(manifest.tables());
        names.add(table.name());
        Manifest next = new Manifest(lastSequence, names);
        next.save(directory);
        faults.check(FaultInjector.Point.AFTER_MANIFEST_COMMIT);
        manifest = next;
        tables.add(table);
        memTable.clear();
        memBytes = 0;
        wal.reset();
        faults.check(FaultInjector.Point.AFTER_WAL_RESET);
    }

    /** 全量压缩覆盖所有旧表，因此可以安全丢弃 tombstone。 */
    public synchronized void compact() throws IOException {
        ensureOpen();
        try {
            flushInternal();
            compactInternal();
        } catch (IOException e) {
            failure = e;
            throw e;
        }
    }

    private void compactInternal() throws IOException {
        if (tables.isEmpty()) {
            return;
        }
        long upperBound = tables.stream().mapToLong(SSTable::count).sum();
        SSTable replacement;
        try (EntryStream merged = new MergeStream(tableStreams(null, null), true)) {
            replacement = SSTable.write(directory, merged, upperBound, options.indexStride());
        }
        faults.check(FaultInjector.Point.AFTER_COMPACTION_OUTPUT);
        List<String> names = replacement == null ? List.of() : List.of(replacement.name());
        Manifest next = new Manifest(manifest.checkpoint(), names);
        next.save(directory);
        faults.check(FaultInjector.Point.AFTER_COMPACTION_MANIFEST);
        manifest = next;
        tables.clear();
        if (replacement != null) {
            tables.add(replacement);
        }
        cleanupOrphans(); // 必须在 MANIFEST 提交之后删除旧表。
    }

    private List<EntryStream> tableStreams(String from, String to) throws IOException {
        List<EntryStream> sources = new ArrayList<>();
        try {
            for (SSTable table : tables) {
                sources.add(table.stream(from, to));
            }
            return sources;
        } catch (IOException e) {
            for (EntryStream stream : sources) {
                try { stream.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
            }
            throw e;
        }
    }

    private void cleanupOrphans() {
        Set<String> live = new HashSet<>(manifest.tables());
        try (var files = Files.list(directory)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                boolean obsoleteTable = DiskIO.isTableName(name) && !live.contains(name);
                boolean temporaryTable = name.endsWith(".tmp")
                        && DiskIO.isTableName(name.substring(0, name.length() - 4));
                boolean temporaryManifest = name.matches("manifest-[0-9a-f-]{36}\\.tmp");
                if (obsoleteTable || temporaryTable || temporaryManifest) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 删除失败不影响逻辑正确性，下次启动/压缩再次尝试。
                    }
                }
            }
        } catch (IOException ignored) {
            // 只做提交后的垃圾回收；不得影响已提交的数据。
        }
    }

    public record Stats(long lastSequence, long checkpoint, int memTableEntries, long memTableBytes,
                        long walBytes, int sstableCount, long sstableEntries, long sstableBytes) { }

    public synchronized Stats stats() throws IOException {
        ensureOpen();
        long bytes = 0;
        for (SSTable table : tables) { bytes += table.size(); }
        return new Stats(lastSequence, manifest.checkpoint(), memTable.size(), memBytes, wal.size(),
                tables.size(), tables.stream().mapToLong(SSTable::count).sum(), bytes);
    }

    private void ensureOpen() throws IOException {
        if (closed) { throw new IOException("Database is closed"); }
        if (failure != null) {
            throw new IOException("Previous I/O operation failed; close and reopen the database", failure);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) { return; }
        closed = true;
        IOException error = null;
        List<AutoCloseable> resources = new ArrayList<>();
        if (wal != null) { resources.add(wal); }
        if (directoryLock != null) { resources.add(directoryLock); }
        if (lockChannel != null) { resources.add(lockChannel); }
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                IOException current = e instanceof IOException io ? io : new IOException(e);
                if (error == null) { error = current; } else { error.addSuppressed(current); }
            }
        }
        if (error != null) { throw error; }
    }
}

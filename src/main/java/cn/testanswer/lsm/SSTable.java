package cn.testanswer.lsm;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 不可变的有序文件：校验头、记录区、带校验的稀疏索引与 Bloom 元数据。 */
final class SSTable {
    static final int HEADER_BYTES = 32;
    private static final int MAGIC = 0x4c535354; // LSST
    private static final int VERSION = 1;
    private static final int MAX_METADATA_BYTES = 64 * 1024 * 1024;

    private record IndexEntry(String key, long offset) { }

    private final Path path;
    private final long count;
    private final long dataEnd;
    private final int stride;
    private final List<IndexEntry> index;
    private final BloomFilter bloom;
    private long maxSequence;

    private SSTable(Path path, long count, long dataEnd, int stride,
                    List<IndexEntry> index, BloomFilter bloom) {
        this.path = path;
        this.count = count;
        this.dataEnd = dataEnd;
        this.stride = stride;
        this.index = List.copyOf(index);
        this.bloom = bloom;
    }

    static SSTable write(Path directory, EntryStream source, long expectedCount, int stride) throws IOException {
        String name = DiskIO.newTableName();
        Path target = directory.resolve(name);
        Path temporary = directory.resolve(name + ".tmp");
        BloomFilter bloom = new BloomFilter(expectedCount);
        List<IndexEntry> index = new ArrayList<>();
        long count = 0;
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            DiskIO.writeFully(channel, ByteBuffer.allocate(HEADER_BYTES));
            String previousKey = null;
            Entry entry;
            while ((entry = source.next()) != null) {
                if (previousKey != null && previousKey.compareTo(entry.key()) >= 0) {
                    throw new IOException("SSTable input must have unique, sorted keys");
                }
                if (count % stride == 0) {
                    index.add(new IndexEntry(entry.key(), channel.position()));
                }
                DiskIO.writeFully(channel, Frames.encode(RecordCodec.encode(entry)));
                bloom.add(entry.key());
                previousKey = entry.key();
                count++;
            }
            if (count > 0) {
                long indexOffset = channel.position();
                byte[] metadata = encodeMetadata(index, bloom);
                if (metadata.length > MAX_METADATA_BYTES) {
                    throw new IOException("SSTable metadata exceeds 64 MiB");
                }
                DiskIO.writeFully(channel, Frames.encode(metadata));
                ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
                header.putInt(MAGIC).putInt(VERSION).putLong(count).putLong(indexOffset).putInt(stride);
                header.putInt(Frames.checksum(Arrays.copyOf(header.array(), HEADER_BYTES - 4))).flip();
                channel.position(0);
                DiskIO.writeFully(channel, header);
                channel.force(true);
            }
        }
        if (count == 0) {
            Files.deleteIfExists(temporary);
            return null;
        }
        DiskIO.publish(temporary, target);
        return open(target);
    }

    private static byte[] encodeMetadata(List<IndexEntry> index, BloomFilter bloom) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(index.size());
            for (IndexEntry entry : index) {
                byte[] key = Utf8.encode(entry.key());
                out.writeInt(key.length);
                out.write(key);
                out.writeLong(entry.offset());
            }
            byte[] bits = bloom.bytes();
            out.writeInt(BloomFilter.HASH_COUNT);
            out.writeInt(bits.length);
            out.write(bits);
        }
        return bytes.toByteArray();
    }

    static SSTable open(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (channel.size() < HEADER_BYTES + Frames.HEADER_BYTES) {
                throw new CorruptStoreException("SSTable is too short: " + path.getFileName());
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            DiskIO.readFully(channel, header, 0);
            int magic = header.getInt();
            int version = header.getInt();
            long count = header.getLong();
            long indexOffset = header.getLong();
            int stride = header.getInt();
            int crc = header.getInt();
            if (magic != MAGIC || version != VERSION || count < 1 || stride < 1 || stride > 65536
                    || indexOffset < HEADER_BYTES || indexOffset > channel.size() - Frames.HEADER_BYTES
                    || count > (indexOffset - HEADER_BYTES) / (Frames.HEADER_BYTES + RecordCodec.FIXED_BYTES + 1)
                    || crc != Frames.checksum(Arrays.copyOf(header.array(), HEADER_BYTES - 4))) {
                throw new CorruptStoreException("Invalid SSTable header: " + path.getFileName());
            }
            Frames.Frame frame = Frames.read(channel, indexOffset, channel.size(), MAX_METADATA_BYTES, false);
            if (frame == null || frame.nextOffset() != channel.size() || frame.payload().length < 12) {
                throw new CorruptStoreException("Invalid SSTable metadata frame");
            }
            ByteBuffer input = ByteBuffer.wrap(frame.payload());
            int indexCount = input.getInt();
            if (indexCount != (count - 1) / stride + 1 || indexCount > input.remaining() / 13) {
                throw new CorruptStoreException("Invalid sparse index count");
            }
            List<IndexEntry> index = new ArrayList<>();
            String previousKey = null;
            long previousOffset = -1;
            for (int i = 0; i < indexCount; i++) {
                if (input.remaining() < 4) {
                    throw new CorruptStoreException("Truncated sparse index");
                }
                int length = input.getInt();
                if (length < 1 || length > RecordCodec.MAX_KEY_BYTES || length > input.remaining() - 8) {
                    throw new CorruptStoreException("Invalid sparse index key length");
                }
                byte[] keyBytes = new byte[length];
                input.get(keyBytes);
                String key = Utf8.decode(keyBytes);
                long offset = input.getLong();
                if (offset < HEADER_BYTES || offset >= indexOffset || offset <= previousOffset
                        || (i == 0 && offset != HEADER_BYTES)
                        || (previousKey != null && previousKey.compareTo(key) >= 0)) {
                    throw new CorruptStoreException("Invalid sparse index ordering/offset");
                }
                index.add(new IndexEntry(key, offset));
                previousKey = key;
                previousOffset = offset;
            }
            if (input.remaining() < 8 || input.getInt() != BloomFilter.HASH_COUNT) {
                throw new CorruptStoreException("Invalid Bloom filter metadata");
            }
            int bitBytes = input.getInt();
            if (bitBytes < 8 || bitBytes > BloomFilter.MAX_BYTES || bitBytes != input.remaining()) {
                throw new CorruptStoreException("Invalid Bloom filter length");
            }
            byte[] bits = new byte[bitBytes];
            input.get(bits);
            SSTable table = new SSTable(path, count, indexOffset, stride, index, new BloomFilter(bits));
            table.validateRecords(channel);
            return table;
        }
    }

    private void validateRecords(FileChannel channel) throws IOException {
        long offset = HEADER_BYTES;
        long seen = 0;
        String previous = null;
        while (offset < dataEnd) {
            Frames.Frame frame = Frames.read(channel, offset, dataEnd, RecordCodec.MAX_PAYLOAD_BYTES, false);
            Entry entry = RecordCodec.decode(frame.payload());
            if (seen >= count || (previous != null && previous.compareTo(entry.key()) >= 0)
                    || !bloom.mightContain(entry.key())) {
                throw new CorruptStoreException("Invalid SSTable ordering/count/Bloom filter");
            }
            if (seen % stride == 0) {
                IndexEntry expected = index.get((int) (seen / stride));
                if (expected.offset() != offset || !expected.key().equals(entry.key())) {
                    throw new CorruptStoreException("Sparse index does not match SSTable records");
                }
            }
            maxSequence = Math.max(maxSequence, entry.sequence());
            previous = entry.key();
            offset = frame.nextOffset();
            seen++;
        }
        if (seen != count) {
            throw new CorruptStoreException("SSTable entry count mismatch");
        }
    }

    Entry get(String key) throws IOException {
        if (!bloom.mightContain(key)) {
            return null;
        }
        try (EntryStream stream = stream(key, null)) {
            Entry entry = stream.next();
            return entry != null && entry.key().equals(key) ? entry : null;
        }
    }

    EntryStream stream(String fromInclusive, String toExclusive) throws IOException {
        return new Cursor(fromInclusive, toExclusive);
    }

    private long startOffset(String from) {
        if (from == null) {
            return HEADER_BYTES;
        }
        int low = 0;
        int high = index.size() - 1;
        int floor = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (index.get(mid).key().compareTo(from) <= 0) {
                floor = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return index.get(floor).offset();
    }

    String name() { return path.getFileName().toString(); }
    long count() { return count; }
    long maxSequence() { return maxSequence; }
    long size() throws IOException { return Files.size(path); }

    private final class Cursor implements EntryStream {
        private final FileChannel channel;
        private final String from;
        private final String to;
        private long offset;

        private Cursor(String from, String to) throws IOException {
            this.from = from;
            this.to = to;
            offset = startOffset(from);
            channel = FileChannel.open(path, StandardOpenOption.READ);
        }

        @Override
        public Entry next() throws IOException {
            while (offset < dataEnd) {
                Frames.Frame frame = Frames.read(channel, offset, dataEnd, RecordCodec.MAX_PAYLOAD_BYTES, false);
                Entry entry = RecordCodec.decode(frame.payload());
                offset = frame.nextOffset();
                if (to != null && entry.key().compareTo(to) >= 0) {
                    offset = dataEnd;
                    return null;
                }
                if (from == null || entry.key().compareTo(from) >= 0) {
                    return entry;
                }
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}

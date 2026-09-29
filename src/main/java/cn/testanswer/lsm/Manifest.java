package cn.testanswer.lsm;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 只有此文件列出的 SSTable 才是已提交的数据。 */
record Manifest(long checkpoint, List<String> tables) {
    static final String FILE_NAME = "MANIFEST";
    private static final int MAGIC = 0x4c4d414e; // LMAN
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    Manifest {
        tables = List.copyOf(tables);
    }

    static Manifest load(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve(FILE_NAME), StandardOpenOption.READ)) {
            Frames.Frame frame = Frames.read(channel, 0, channel.size(), MAX_BYTES, false);
            if (frame == null || frame.nextOffset() != channel.size() || frame.payload().length < 20) {
                throw new CorruptStoreException("Invalid MANIFEST frame");
            }
            ByteBuffer input = ByteBuffer.wrap(frame.payload());
            int magic = input.getInt();
            int version = input.getInt();
            long checkpoint = input.getLong();
            int count = input.getInt();
            if (magic != MAGIC || version != VERSION || checkpoint < 0 || count < 0
                    || count > input.remaining() / 4) {
                throw new CorruptStoreException("Invalid MANIFEST header");
            }
            List<String> names = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (input.remaining() < 4) {
                    throw new CorruptStoreException("Truncated MANIFEST filename");
                }
                int length = input.getInt();
                if (length < 1 || length > input.remaining()) {
                    throw new CorruptStoreException("Invalid MANIFEST filename length");
                }
                byte[] bytes = new byte[length];
                input.get(bytes);
                String name = Utf8.decode(bytes);
                if (!DiskIO.isTableName(name)) {
                    throw new CorruptStoreException("Invalid SSTable filename in MANIFEST");
                }
                names.add(name);
            }
            if (input.hasRemaining() || new HashSet<>(names).size() != names.size()) {
                throw new CorruptStoreException("Trailing bytes or duplicate tables in MANIFEST");
            }
            return new Manifest(checkpoint, names);
        }
    }

    void save(Path directory) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(checkpoint);
            output.writeInt(tables.size());
            for (String table : tables) {
                byte[] name = Utf8.encode(table);
                output.writeInt(name.length);
                output.write(name);
            }
        }
        if (bytes.size() > MAX_BYTES) {
            throw new IOException("MANIFEST is too large; run compaction");
        }
        Path temporary = directory.resolve("manifest-" + UUID.randomUUID() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            DiskIO.writeFully(channel, Frames.encode(bytes.toByteArray()));
            channel.force(true);
        }
        DiskIO.publish(temporary, directory.resolve(FILE_NAME));
    }
}

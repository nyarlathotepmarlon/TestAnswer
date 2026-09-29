package cn.testanswer.lsm;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

final class Wal implements AutoCloseable {
    static final String FILE_NAME = "WAL";
    private final FileChannel channel;

    Wal(Path directory) throws IOException {
        FileChannel opened = FileChannel.open(directory.resolve(FILE_NAME), StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            opened.force(true);
            DiskIO.syncDirectory(directory);
            channel = opened;
        } catch (IOException | RuntimeException e) {
            try { opened.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
            throw e;
        }
    }

    long recover(long checkpoint, Consumer<Entry> consume) throws IOException {
        long offset = 0;
        long lastSequence = 0;
        long maximum = checkpoint;
        long size = channel.size();
        while (offset < size) {
            Frames.Frame frame = Frames.read(channel, offset, size, RecordCodec.MAX_PAYLOAD_BYTES, true);
            if (frame == null) {
                // 只忽略最后一条未完整写入的记录；完整记录 CRC 错误必须报错。
                channel.truncate(offset);
                channel.force(true);
                break;
            }
            Entry entry = RecordCodec.decode(frame.payload());
            if (entry.sequence() <= lastSequence) {
                throw new CorruptStoreException("WAL sequence must be strictly increasing");
            }
            lastSequence = entry.sequence();
            maximum = Math.max(maximum, lastSequence);
            if (lastSequence > checkpoint) {
                consume.accept(entry);
            }
            offset = frame.nextOffset();
        }
        channel.position(channel.size());
        return maximum;
    }

    void append(byte[] encodedRecord) throws IOException {
        DiskIO.writeFully(channel, Frames.encode(encodedRecord));
        channel.force(true);
    }

    long size() throws IOException {
        return channel.size();
    }

    void reset() throws IOException {
        channel.truncate(0);
        channel.position(0);
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}

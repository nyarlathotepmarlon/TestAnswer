package cn.testanswer.lsm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.CRC32C;

/** length + ~length + CRC32C(payload) + payload，均使用大端序。 */
final class Frames {
    static final int HEADER_BYTES = 12;

    private Frames() { }

    record Frame(byte[] payload, long nextOffset) { }

    static int checksum(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    static ByteBuffer encode(byte[] payload) {
        return ByteBuffer.allocate(HEADER_BYTES + payload.length)
                .putInt(payload.length).putInt(~payload.length).putInt(checksum(payload)).put(payload).flip();
    }

    static Frame read(FileChannel channel, long offset, long end, int maxBytes, boolean allowPartialTail)
            throws IOException {
        if (offset == end) {
            return null;
        }
        if (offset < 0 || offset > end) {
            throw new CorruptStoreException("Invalid frame offset: " + offset);
        }
        if (end - offset < HEADER_BYTES) {
            return incomplete(allowPartialTail, offset);
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        DiskIO.readFully(channel, header, offset);
        int length = header.getInt();
        int complement = header.getInt();
        int expectedCrc = header.getInt();
        if (length < 0 || length > maxBytes || complement != ~length) {
            throw new CorruptStoreException("Invalid frame length/header at " + offset);
        }
        if (end - offset - HEADER_BYTES < length) {
            return incomplete(allowPartialTail, offset);
        }
        ByteBuffer payload = ByteBuffer.allocate(length);
        DiskIO.readFully(channel, payload, offset + HEADER_BYTES);
        if (checksum(payload.array()) != expectedCrc) {
            throw new CorruptStoreException("CRC32C mismatch at " + offset);
        }
        return new Frame(payload.array(), offset + HEADER_BYTES + length);
    }

    private static Frame incomplete(boolean allowed, long offset) throws CorruptStoreException {
        if (allowed) {
            return null;
        }
        throw new CorruptStoreException("Incomplete frame at " + offset);
    }
}

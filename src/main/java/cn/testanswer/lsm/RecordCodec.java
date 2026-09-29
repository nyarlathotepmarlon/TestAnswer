package cn.testanswer.lsm;

import java.nio.ByteBuffer;

final class RecordCodec {
    static final int MAX_KEY_BYTES = 64 * 1024;
    static final int MAX_VALUE_BYTES = 4 * 1024 * 1024;
    static final int FIXED_BYTES = Long.BYTES + 1 + Integer.BYTES * 2;
    static final int MAX_PAYLOAD_BYTES = FIXED_BYTES + MAX_KEY_BYTES + MAX_VALUE_BYTES;

    private RecordCodec() { }

    static void validateKey(String key) {
        int length = Utf8.encode(key).length;
        if (length == 0 || length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("Key must contain 1..65536 UTF-8 bytes");
        }
    }

    static byte[] encode(Entry entry) {
        validateKey(entry.key());
        if (entry.sequence() < 1) {
            throw new IllegalArgumentException("Sequence must be positive");
        }
        byte[] key = Utf8.encode(entry.key());
        byte[] value = entry.deleted() ? new byte[0] : Utf8.encode(entry.value());
        if (value.length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("Value exceeds 4 MiB");
        }
        return ByteBuffer.allocate(FIXED_BYTES + key.length + value.length)
                .putLong(entry.sequence()).put((byte) (entry.deleted() ? 2 : 1))
                .putInt(key.length).putInt(entry.deleted() ? -1 : value.length).put(key).put(value).array();
    }

    static Entry decode(byte[] payload) throws CorruptStoreException {
        if (payload.length < FIXED_BYTES) {
            throw new CorruptStoreException("Record is too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        long sequence = buffer.getLong();
        byte operation = buffer.get();
        int keyLength = buffer.getInt();
        int valueLength = buffer.getInt();
        boolean deleted = operation == 2;
        if (sequence < 1 || keyLength < 1 || keyLength > MAX_KEY_BYTES
                || (operation != 1 && operation != 2)
                || (deleted ? valueLength != -1 : valueLength < 0 || valueLength > MAX_VALUE_BYTES)
                || (long) keyLength + (deleted ? 0 : valueLength) != buffer.remaining()) {
            throw new CorruptStoreException("Invalid record fields");
        }
        byte[] key = new byte[keyLength];
        buffer.get(key);
        byte[] value = new byte[deleted ? 0 : valueLength];
        buffer.get(value);
        return new Entry(sequence, Utf8.decode(key), deleted ? null : Utf8.decode(value));
    }
}

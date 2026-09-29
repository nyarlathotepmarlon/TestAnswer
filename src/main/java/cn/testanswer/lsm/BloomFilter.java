package cn.testanswer.lsm;

/** 每个 key 约 10 bit，7 次双重散列；仅用于否定查询，命中仍需查 SSTable。 */
final class BloomFilter {
    static final int HASH_COUNT = 7;
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private final byte[] bits;

    BloomFilter(long expectedEntries) {
        long bounded = Math.min(Math.max(expectedEntries, 1), MAX_BYTES * 8L / 10);
        bits = new byte[(int) Math.max(8, (bounded * 10 + 7) / 8)];
    }

    BloomFilter(byte[] bits) throws CorruptStoreException {
        if (bits.length < 8 || bits.length > MAX_BYTES) {
            throw new CorruptStoreException("Invalid Bloom filter size");
        }
        this.bits = bits.clone();
    }

    byte[] bytes() {
        return bits.clone();
    }

    void add(String key) {
        long h1 = hash(key);
        long h2 = Long.rotateLeft(h1, 29) | 1L;
        for (int i = 0; i < HASH_COUNT; i++) {
            int bit = bit(h1 + i * h2);
            bits[bit >>> 3] |= (byte) (1 << (bit & 7));
        }
    }

    boolean mightContain(String key) {
        long h1 = hash(key);
        long h2 = Long.rotateLeft(h1, 29) | 1L;
        for (int i = 0; i < HASH_COUNT; i++) {
            int bit = bit(h1 + i * h2);
            if ((bits[bit >>> 3] & (1 << (bit & 7))) == 0) {
                return false;
            }
        }
        return true;
    }

    private int bit(long hash) {
        return (int) Long.remainderUnsigned(hash, bits.length * 8L);
    }

    private static long hash(String key) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : Utf8.encode(key)) {
            hash = (hash ^ (value & 0xff)) * 0x100000001b3L;
        }
        // avalanche：改善前缀相近 key 的分布。
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        return hash ^ (hash >>> 33);
    }
}

package cn.testanswer.lsm;

/** 阈值按编码后的字节计算；autoCompactTables=0 表示只手动压缩。 */
public record StoreOptions(long memTableBytes, long walBytes, int indexStride, int autoCompactTables) {
    public static final StoreOptions DEFAULT = new StoreOptions(64 * 1024, 1024 * 1024, 32, 4);

    public StoreOptions {
        if (memTableBytes < 1 || walBytes < 1) {
            throw new IllegalArgumentException("MemTable/WAL thresholds must be positive");
        }
        if (indexStride < 1 || indexStride > 65536) {
            throw new IllegalArgumentException("indexStride must be between 1 and 65536");
        }
        if (autoCompactTables != 0 && autoCompactTables < 2) {
            throw new IllegalArgumentException("autoCompactTables must be 0 or at least 2");
        }
    }
}

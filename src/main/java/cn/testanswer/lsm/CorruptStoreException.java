package cn.testanswer.lsm;

import java.io.IOException;

/** 文件损坏时显式失败，避免将损坏的数据误报为不存在。 */
public final class CorruptStoreException extends IOException {
    public CorruptStoreException(String message) {
        super(message);
    }

    public CorruptStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

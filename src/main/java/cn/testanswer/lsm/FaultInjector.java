package cn.testanswer.lsm;

import java.io.IOException;

/** 仅包内测试使用，不暴露在 CLI 或公共存储 API 中。 */
@FunctionalInterface
interface FaultInjector {
    FaultInjector NONE = point -> { };

    enum Point {
        AFTER_WAL_SYNC,
        AFTER_SSTABLE_SYNC,
        AFTER_MANIFEST_COMMIT,
        AFTER_WAL_RESET,
        AFTER_COMPACTION_OUTPUT,
        AFTER_COMPACTION_MANIFEST
    }

    void check(Point point) throws IOException;
}

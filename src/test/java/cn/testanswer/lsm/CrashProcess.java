package cn.testanswer.lsm;

import java.io.IOException;
import java.nio.file.Path;

/** 由测试启动的独立 JVM；halt 不执行 finally、close 或 shutdown hook。 */
public final class CrashProcess {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        if (args[1].equals("probe-lock")) {
            try (LsmStore ignored = LsmStore.open(directory)) {
                System.exit(99);
            } catch (IOException e) {
                System.err.println(e.getMessage());
                System.exit(e.getMessage().contains("already open") ? 24 : 98);
            }
            return;
        }
        FaultInjector.Point point = FaultInjector.Point.valueOf(args[1]);
        seed(directory);
        try (LsmStore store = LsmStore.open(directory, LsmStoreTest.MANUAL, actual -> {
            if (actual == point) { Runtime.getRuntime().halt(23); }
        })) {
            exercise(store, point);
        }
        throw new IllegalStateException("Crash point was not reached");
    }

    static void seed(Path directory) throws IOException {
        try (LsmStore store = LsmStore.open(directory, LsmStoreTest.MANUAL)) {
            store.put("stable", "稳定");
            store.put("gone", "旧值");
            store.flush();
        }
    }

    static void exercise(LsmStore store, FaultInjector.Point point) throws IOException {
        store.put("new", "新值");
        if (point != FaultInjector.Point.AFTER_WAL_SYNC) {
            store.delete("gone");
            store.flush();
            if (point == FaultInjector.Point.AFTER_COMPACTION_OUTPUT
                    || point == FaultInjector.Point.AFTER_COMPACTION_MANIFEST) {
                store.compact();
            }
        }
    }
}

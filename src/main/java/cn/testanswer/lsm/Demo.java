package cn.testanswer.lsm;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** IDEA 一键运行的演示，包含可执行断言，保留演示目录供检查。 */
public final class Demo {
    private Demo() { }

    public static void main(String[] args) throws IOException {
        run(new PrintStream(System.out, true, StandardCharsets.UTF_8));
    }

    static void run(PrintStream out) throws IOException {
        Files.createDirectories(Path.of("data"));
        Path directory = Files.createTempDirectory(Path.of("data"), "demo-").toAbsolutePath();
        StoreOptions options = new StoreOptions(1024, 4096, 2, 0);
        out.println("=== Java LSM-Tree 完整演示 ===");
        out.println("数据目录: " + directory);
        try (LsmStore store = LsmStore.open(directory, options)) {
            store.put("user:001", "张三");
            store.put("user:002", "李四");
            store.put("user:003", "王五");
            store.flush();
            store.put("user:001", "张三（已更新）");
            store.delete("user:002");
            store.flush();
            store.put("user:004", "中文与 emoji 🚀");
            out.println("1. 写入、覆盖、删除: " + store.scan());
            out.println("2. 关闭前统计（最后一条仅在 WAL/MemTable）: " + Json.stats(store.stats()));
            check(store.stats().memTableEntries() == 1, "WAL-only record expected");
        }
        try (LsmStore store = LsmStore.open(directory, options)) {
            check(store.get("user:004").orElseThrow().equals("中文与 emoji 🚀"), "WAL recovery");
            check(store.get("user:002").isEmpty(), "Deleted key must stay deleted");
            out.println("3. 重新打开，WAL 恢复成功: " + store.get("user:004").orElseThrow());
            out.println("4. 范围查询 [user:001, user:004): " + store.scan("user:001", "user:004", 100));
            store.flush();
            out.println("5. 压缩前: " + Json.stats(store.stats()));
            store.compact();
            out.println("6. 压缩后: " + Json.stats(store.stats()));
            check(store.stats().sstableCount() == 1 && store.stats().sstableEntries() == 3, "Compaction reclamation");
            check(store.scan().equals(Map.of("user:001", "张三（已更新）", "user:003", "王五",
                    "user:004", "中文与 emoji 🚀")), "Compaction result");
        }
        try (LsmStore store = LsmStore.open(directory, options)) {
            out.println("7. 压缩后再次重启: " + store.scan());
            check(store.get("user:002").isEmpty() && store.scan().size() == 3, "Restart after compaction");
        }
        out.println("所有演示断言通过。真实 JVM 非正常退出恢复由 RecoveryTest 验证。");
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException("Demo failed: " + message); }
    }
}

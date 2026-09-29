package cn.testanswer.lsm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** IDEA 可直接运行此 main；无参数时进入交互式 CLI。 */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        int code = run(args, System.in, out, err);
        if (code != 0) { System.exit(code); }
    }

    static int run(String[] args, InputStream input, PrintStream out, PrintStream err) {
        try {
            Path directory = Path.of("data", "store");
            long memBytes = StoreOptions.DEFAULT.memTableBytes();
            long walBytes = StoreOptions.DEFAULT.walBytes();
            int stride = StoreOptions.DEFAULT.indexStride();
            int compactAfter = StoreOptions.DEFAULT.autoCompactTables();
            int i = 0;
            while (i < args.length && args[i].startsWith("--")) {
                String option = args[i++];
                if (option.equals("--help")) { help(out); return 0; }
                if (i == args.length) { throw new IllegalArgumentException("Missing value for " + option); }
                String value = args[i++];
                switch (option) {
                    case "--dir" -> directory = Path.of(value);
                    case "--memtable-bytes" -> memBytes = Long.parseLong(value);
                    case "--wal-bytes" -> walBytes = Long.parseLong(value);
                    case "--index-stride" -> stride = Integer.parseInt(value);
                    case "--compact-after" -> compactAfter = Integer.parseInt(value);
                    default -> throw new IllegalArgumentException("Unknown option: " + option);
                }
            }
            List<String> command = i == args.length ? List.of("shell") : Arrays.asList(args).subList(i, args.length);
            if (command.get(0).equals("help")) { requireSize(command, 1); help(out); return 0; }
            if (command.get(0).equals("demo")) {
                requireSize(command, 1);
                Demo.run(out);
                return 0;
            }
            StoreOptions options = new StoreOptions(memBytes, walBytes, stride, compactAfter);
            try (LsmStore store = LsmStore.open(directory, options)) {
                if (command.get(0).equals("shell")) {
                    requireSize(command, 1);
                    return shell(store, input, out, err);
                }
                return execute(store, command, out);
            }
        } catch (IllegalArgumentException e) {
            error(err, "ARGUMENT", e.getMessage());
            return 2;
        } catch (IOException e) {
            error(err, "IO", e.getMessage());
            return 1;
        }
    }

    private static int execute(LsmStore store, List<String> command, PrintStream out) throws IOException {
        switch (command.get(0)) {
            case "put" -> {
                requireSize(command, 3);
                store.put(command.get(1), command.get(2));
                out.println("{\"ok\":true,\"operation\":\"put\",\"key\":" + Json.quote(command.get(1)) + "}");
            }
            case "get" -> {
                requireSize(command, 2);
                Optional<String> value = store.get(command.get(1));
                out.println("{\"key\":" + Json.quote(command.get(1)) + ",\"found\":" + value.isPresent()
                        + ",\"value\":" + Json.quote(value.orElse(null)) + "}");
                return value.isPresent() ? 0 : 3;
            }
            case "delete" -> {
                requireSize(command, 2);
                store.delete(command.get(1));
                out.println("{\"ok\":true,\"operation\":\"delete\",\"key\":" + Json.quote(command.get(1)) + "}");
            }
            case "scan" -> scan(store, command, out);
            case "flush" -> {
                requireSize(command, 1);
                store.flush();
                out.println("{\"ok\":true,\"operation\":\"flush\"}");
            }
            case "compact" -> {
                requireSize(command, 1);
                store.compact();
                out.println("{\"ok\":true,\"operation\":\"compact\"}");
            }
            case "stats" -> {
                requireSize(command, 1);
                out.println(Json.stats(store.stats()));
            }
            case "help" -> { requireSize(command, 1); help(out); }
            default -> throw new IllegalArgumentException("Unknown command: " + command.get(0));
        }
        return 0;
    }

    private static void scan(LsmStore store, List<String> command, PrintStream out) throws IOException {
        String from = null;
        String to = null;
        int limit = 100;
        for (int i = 1; i < command.size(); i += 2) {
            String option = command.get(i);
            if (i + 1 >= command.size()) { throw new IllegalArgumentException("Missing value for " + option); }
            String value = command.get(i + 1);
            switch (option) {
                case "--from" -> from = value;
                case "--to" -> to = value;
                case "--limit" -> limit = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("Unknown scan option: " + option);
            }
        }
        Map<String, String> entries = store.scan(from, to, limit);
        StringBuilder json = new StringBuilder("{\"entries\":[");
        boolean first = true;
        for (var entry : entries.entrySet()) {
            if (!first) { json.append(','); }
            json.append("{\"key\":").append(Json.quote(entry.getKey()))
                    .append(",\"value\":").append(Json.quote(entry.getValue())).append('}');
            first = false;
        }
        out.println(json.append("],\"count\":").append(entries.size()).append('}'));
    }

    private static int shell(LsmStore store, InputStream input, PrintStream out, PrintStream err) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        out.println("LSM-Tree CLI（UTF-8）。输入 help 查看命令，exit 退出；含空格的文本请加引号。");
        while (true) {
            out.print("lsm> ");
            out.flush();
            String line = reader.readLine();
            if (line == null) { out.println(); return 0; }
            try {
                List<String> command = ShellWords.parse(line);
                if (command.isEmpty()) { continue; }
                if (command.get(0).equals("exit") || command.get(0).equals("quit")) {
                    requireSize(command, 1);
                    return 0;
                }
                execute(store, command, out);
            } catch (IllegalArgumentException e) {
                error(err, "ARGUMENT", e.getMessage());
            }
        }
    }

    private static void requireSize(List<String> arguments, int size) {
        if (arguments.size() != size) {
            throw new IllegalArgumentException(arguments.get(0) + " expects " + (size - 1) + " argument(s)");
        }
    }

    private static void error(PrintStream err, String code, String message) {
        err.println("{\"error\":" + Json.quote(code) + ",\"message\":" + Json.quote(message) + "}");
    }

    private static void help(PrintStream out) {
        out.println("""
                Java 17 LSM-Tree — 问题三
                用法: java -jar target/lsm-tree-1.0.0.jar [全局选项] 命令
                全局选项（放在命令前）:
                  --dir PATH             数据目录，默认 data/store
                  --memtable-bytes N     内存记录编码字节阈值，默认 65536
                  --wal-bytes N          WAL 字节阈值，默认 1048576（防止热 key 覆写撑大日志）
                  --index-stride N       稀疏索引间隔，默认 32
                  --compact-after N      自动压缩表数，默认 4；0 禁用自动压缩
                命令:
                  put KEY VALUE          写入/覆盖，可存储中文和空字符串
                  get KEY                查询；不存在时退出码为 3
                  delete KEY             幂等删除
                  scan [--from KEY] [--to KEY] [--limit N]
                                         左闭右开，按 Java 字符串顺序，默认最多 100 条
                  flush                  强制刷盘
                  compact                全量压缩，回收覆盖版本和删除标记
                  stats                  查看内存、WAL、SSTable 统计
                  shell                  交互模式（无参数时默认进入）
                  demo                   在新的 data/demo-* 目录中运行完整演示
                  help                   显示帮助
                退出码: 0 成功；1 存储 I/O 失败；2 参数错误；3 get 未找到。
                """);
    }
}

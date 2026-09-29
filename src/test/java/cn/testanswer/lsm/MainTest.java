package cn.testanswer.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path directory;

    private record Result(int code, String out, String err) { }

    private Result command(String... args) {
        return invoke("", args);
    }

    private Result invoke(String input, String... args) {
        List<String> all = new ArrayList<>(List.of("--dir", directory.toString()));
        all.addAll(Arrays.asList(args));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void commandsPersistAcrossIndependentInvocations() {
        assertEquals(0, command("put", "姓名", "张三").code());
        Result get = command("get", "姓名");
        assertEquals(0, get.code());
        assertEquals("{\"key\":\"姓名\",\"found\":true,\"value\":\"张三\"}", get.out().strip());
        assertEquals(0, command("flush").code());
        assertEquals(0, command("delete", "姓名").code());
        assertEquals(0, command("compact").code());
        Result missing = command("get", "姓名");
        assertEquals(3, missing.code());
        assertTrue(missing.out().contains("\"found\":false"));
        assertTrue(missing.err().isEmpty());
    }

    @Test
    void scanCommandSupportsBoundsLimitsAndSortedJson() {
        command("put", "c", "3"); command("put", "a", "1"); command("put", "b", "2");
        Result result = command("scan", "--from", "b", "--to", "d", "--limit", "1");
        assertEquals(0, result.code());
        assertEquals("{\"entries\":[{\"key\":\"b\",\"value\":\"2\"}],\"count\":1}", result.out().strip());
        assertEquals(2, command("scan", "--limit", "0").code());
        assertEquals(2, command("scan", "--from").code());
        assertEquals(2, command("scan", "--unknown", "x").code());
    }

    @Test
    void invalidCommandsAndOptionsReturnStructuredErrors() {
        assertEquals(2, command("put", "key").code());
        assertEquals(2, command("unknown").code());
        assertEquals(2, command("--memtable-bytes", "no-number", "stats").code());
        assertEquals(2, command("--unknown", "x", "stats").code());
        assertEquals(2, command("--wal-bytes").code());
        Result invalid = command("put", "", "value");
        assertTrue(invalid.err().contains("\"error\":\"ARGUMENT\""));
        assertTrue(invalid.out().isEmpty());
    }

    @Test
    void statsCommandReflectsDurabilityAndCompaction() {
        command("put", "key", "value");
        assertTrue(command("stats").out().contains("\"memTableEntries\":1"));
        command("flush");
        Result stats = command("stats");
        assertTrue(stats.out().contains("\"sstableCount\":1"));
        assertTrue(stats.out().contains("\"walBytes\":0"));
    }

    @Test
    void helpHasNoStorageSideEffects() {
        assertEquals(0, command("help").code());
        assertEquals(0, command("--help").code());
        assertFalse(java.nio.file.Files.exists(directory.resolve("MANIFEST")));
    }

    @Test
    void shellSupportsQuotedUnicodeEmptyValuesEscapesAndErrorRecovery() {
        Result shell = invoke("""
                put "中文 key" "带 空格的值"
                put empty ""
                put escaped "line1\\nline2"
                invalid-command
                get "中文 key"
                get empty
                get escaped
                exit
                """, "shell");
        assertEquals(0, shell.code());
        assertTrue(shell.out().contains("带 空格的值"));
        assertTrue(shell.out().contains("\"value\":\"\""));
        assertTrue(shell.out().contains("line1\\nline2"));
        assertTrue(shell.err().contains("Unknown command"));
        assertEquals(0, command("get", "中文 key").code());
    }

    @Test
    void noCommandEntersShellAndEofExitsCleanly() {
        assertEquals(0, invoke("put key value\n").code());
        assertEquals(0, command("get", "key").code());
    }

    @Test
    void lockedDatabaseIsReportedAsIoError() throws Exception {
        try (LsmStore ignored = LsmStore.open(directory)) {
            Result result = command("stats");
            assertEquals(1, result.code());
            assertTrue(result.err().contains("\"error\":\"IO\""));
        }
    }

    @Test
    void tokenizerPreservesSingleQuotesAndWindowsBackslashes() {
        assertEquals(List.of("put", "key", "C:\\中文目录\\file.txt"), ShellWords.parse("put key 'C:\\中文目录\\file.txt'"));
        assertEquals(List.of("put", "key", "a\"b\\c"), ShellWords.parse("put key \"a\\\"b\\\\c\""));
        assertThrows(IllegalArgumentException.class, () -> ShellWords.parse("put key \"unclosed"));
    }

    @Test
    void jsonEscapesControlsQuotesAndEmojiWithoutChangingStoredValue() {
        assertEquals("\"\\\"\\\\\\n\\u0000\\ud83d\\ude80\"", Json.quote("\"\\\n\0🚀"));
        String value = "\"quote\"\nline\t🚀";
        assertEquals(0, command("put", "key", value).code());
        assertTrue(command("get", "key").out().contains(Json.quote(value)));
    }
}

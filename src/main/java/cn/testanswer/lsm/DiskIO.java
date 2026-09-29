package cn.testanswer.lsm;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

final class DiskIO {
    private DiskIO() { }

    static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    static void readFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of file at " + offset);
            }
            offset += read;
        }
        buffer.flip();
    }

    static void publish(Path temporary, Path target) throws IOException {
        // 不退化为非原子的 copy/delete。MANIFEST 是整个数据库的提交点。
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(target.getParent());
    }

    static void syncDirectory(Path directory) {
        // Windows 的 Java 文件系统通常不允许打开目录；进程崩溃恢复不依赖此项。
        // 因此不宣称跨平台的突然断电持久性，详见 DESIGN.md。
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 尽力同步目录元数据；文件内容的 force(true) 失败不会被忽略。
        }
    }

    static String newTableName() {
        return "sst-" + UUID.randomUUID() + ".sst";
    }

    static boolean isTableName(String name) {
        return name.matches("sst-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.sst");
    }
}

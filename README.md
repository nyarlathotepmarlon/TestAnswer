# Java LSM-Tree 键值存储引擎

2026-09-29 机试，**选择问题三**。使用 Java 17 实现持久化 `put / get / delete / scan`、WAL 崩溃恢复、MemTable 自动刷盘、SSTable 稀疏索引及全量压缩，并完成可选模块：**布隆过滤器**。

运行时仅依赖 JDK，无需数据库、服务端框架或 Docker。JUnit 仅用于测试。支持 Windows 中文路径、中文键值及 UTF-8 文本。

## 在 IntelliJ IDEA 中运行

1. 用 **File → Open** 打开本目录的 `pom.xml`，选择作为 Maven 项目打开，等待依赖导入。
2. **File → Project Structure → Project SDK** 选择 **JDK 17**，Language level 设为 17。Maven Runner 的 JRE 也选择 17。
3. 在 **Settings → Editor → File Encodings** 中将 Project Encoding 设为 **UTF-8**。
4. 运行右上角的 **LSM Demo** 配置，或者直接打开 [Demo.java](src/main/java/cn/testanswer/lsm/Demo.java)，点击 `main` 左侧绿色箭头。演示会依次执行写入、覆盖、删除、WAL 恢复、范围查询、压缩与再次重启。
5. 运行 **LSM CLI** 配置，或者打开 [Main.java](src/main/java/cn/testanswer/lsm/Main.java) 的 `main`，进入交互命令行。在 IDEA 控制台输入命令即可。
6. 测试可右键 `src/test/java` → **Run All Tests**，或在 Maven 面板执行 **Lifecycle → verify**。

仓库已经提供 `.run/` 共享运行配置。如果 IDEA 导入时给模块分配了其他名称，在运行配置的 **Use classpath of module** 中选择导入后的 Maven 模块，或直接从 `main` 启动。

工作目录应为项目根目录；两个运行配置已设置 `-Dfile.encoding=UTF-8`。文件编码、Maven 编译与测试也均显式采用 UTF-8。若 IDEA 没有自动使用 `.mvn/maven.config`，请在 Maven 设置中启用 **Use settings from .mvn/maven.config**，然后重新加载 Maven 项目。

## 在 Windows PowerShell 中构建和运行

已在本机 **Windows 10、Java 17、Maven 3.6.1** 验证。首次构建需要网络下载 Maven 插件和 JUnit；生成的 JAR 可离线运行。

```powershell
# 在当前项目根目录执行
mvn -B -ntp clean verify

# 完整演示，不需要输入参数
java '-Dfile.encoding=UTF-8' -jar target/lsm-tree-1.0.0.jar demo

# 交互模式
java '-Dfile.encoding=UTF-8' -jar target/lsm-tree-1.0.0.jar --dir data/my-store shell
```

**PowerShell 下 `'-Dfile.encoding=UTF-8'` 应保留引号。** 在旧控制台中，如中文输出乱码，先运行：

```powershell
chcp 65001
$OutputEncoding = [Console]::OutputEncoding = [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
```

也可直接运行脚本，自动设置 UTF-8，并将实际运行结果保存到 `docs/results/`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
# 只构建并运行演示：
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-demo.ps1
```

项目的 `.mvn/settings.xml` 使用 Maven Central HTTPS，`.mvn/maven.config` 将依赖缓存放到 `.mvn/repository/`，避免依赖开发机已有的镜像和仓库路径。该缓存不提交 Git、不放进提交包。所有 Maven 命令应在项目根目录执行。

## CLI 使用

交互模式输入：

```text
put user:001 "张三"
put user:002 "李四"
get user:001
put user:001 "张三（已更新）"
scan --from user:001 --to user:003 --limit 10
delete user:002
flush
compact
stats
exit
```

支持单条命令，每次调用都打开同一个数据目录，因此不同进程之间也能验证持久化：

```powershell
java '-Dfile.encoding=UTF-8' -jar target/lsm-tree-1.0.0.jar --dir data/my-store put hello world
java '-Dfile.encoding=UTF-8' -jar target/lsm-tree-1.0.0.jar --dir data/my-store get hello
java '-Dfile.encoding=UTF-8' -jar target/lsm-tree-1.0.0.jar --dir data/my-store scan
java '-Dfile.encoding=UTF-8' -jar target/lsm-tree-1.0.0.jar --dir data/my-store delete hello
```

查询成功输出：

```json
{"key":"hello","found":true,"value":"world"}
```

扫描输出：

```json
{"entries":[{"key":"hello","value":"world"}],"count":1}
```

| 命令 | 说明 |
| --- | --- |
| `put KEY VALUE` | 写入或覆盖，值可以是空字符串 |
| `get KEY` | 查询，区分空字符串与不存在 |
| `delete KEY` | 幂等删除，写入删除标记 |
| `scan [--from KEY] [--to KEY] [--limit N]` | 左闭右开范围扫描，默认最多 100 条 |
| `flush` | 将 MemTable 强制刷入 SSTable |
| `compact` | 将所有 SSTable 全量归并，清除旧版本和删除标记 |
| `stats` | 查看序列号、checkpoint、内存、WAL、SSTable 统计 |
| `shell` | 交互模式，无命令时默认进入 |
| `demo` | 在新的 `data/demo-*` 目录中执行完整演示 |
| `help` / `--help` | 查看帮助 |

全局选项必须放在命令前：

| 选项 | 默认值 | 说明 |
| --- | --- | --- |
| `--dir PATH` | `data/store` | 数据目录，同一目录只能由一个进程打开 |
| `--memtable-bytes N` | `65536` | 当前 MemTable 中最新记录的编码字节阈值 |
| `--wal-bytes N` | `1048576` | WAL 字节阈值，限制持续覆写同一个 key 时的日志增长 |
| `--index-stride N` | `32` | 每 N 条记录保存一个稀疏索引项，范围 1–65536 |
| `--compact-after N` | `4` | 自动刷盘后，表数达到 N 时压缩；0 关闭，其他值须至少为 2 |

`scan` 按 Java `String.compareTo` 排序，区分大小写，不做语言排序或 Unicode 归一化；省略边界表示无界。键长度为 1–65536 UTF-8 字节，值最多 4 MiB。不支持 null 值，删除使用 `delete`。非法 UTF-16 代理项会在写入前被拒绝。

Shell 中双引号支持 `\n`、`\r`、`\t`、`\"`、`\\`；单引号按字面内容读取，适合 Windows 路径；空值可输入 `put empty ""`。单命令模式的引号由 PowerShell/操作系统解析。`demo` 始终创建独立演示目录，不使用 `--dir`。

单命令退出码：`0` 成功，`1` 存储 I/O 错误，`2` 参数错误，`3` `get` 未找到。Shell 遇到参数错误继续接受输入，遇到 I/O 错误退出。

## Java API

```java
try (LsmStore store = LsmStore.open(Path.of("data", "embedded"))) {
    store.put("name", "张三");
    Optional<String> value = store.get("name");
    NavigableMap<String, String> range = store.scan("a", "z", 100);
    store.delete("name");
    store.flush();
    store.compact();
}
```

以上类型使用 `cn.testanswer.lsm.LsmStore`、`java.nio.file.Path`、`java.util.Optional` 和 `java.util.NavigableMap`。公开操作通过同一把实例锁串行化，支持多线程调用。返回的扫描结果是不可修改的快照。

## 需求与实现对应

| 题目要求 | 实现 | 验证 |
| --- | --- | --- |
| CLI `put/get/delete/scan` | `Main`、`ShellWords` | `MainTest` 及独立进程 CLI 验证 |
| 先写 WAL 再写 MemTable、崩溃恢复 | `Wal`、`Frames`、`LsmStore` | `RecoveryTest`，包含真实 JVM `halt` |
| MemTable 阈值刷盘、SSTable 索引 | `SSTable`，持久化稀疏索引 | 阈值触发、索引查找、跨配置重启测试 |
| 合并、回收已删除和被覆盖的 key | `MergeStream`，手动及自动全量压缩 | 表数/记录数/文件回收/压缩后重启测试 |
| 重启恢复、范围查询、压缩后查询测试 | JUnit 5，当前共 52 项测试 | `mvn clean verify` |
| 数据格式、读写路径、恢复流程、压缩策略文档 | [设计文档](docs/DESIGN.md) | 代码与文档一起交付 |
| 可选：布隆过滤器 | `BloomFilter`，随 SSTable 持久化 | 无假阴性、序列化及不存在键过滤测试 |

## 项目结构

```text
TestAnswer/
├── pom.xml                         Maven / Java 17 / JUnit 5
├── .mvn/                           UTF-8、仓库配置
├── .run/                           IDEA 演示与 CLI 配置
├── src/main/java/cn/testanswer/lsm/ 存储引擎、CLI、演示
├── src/test/java/cn/testanswer/lsm/ 单元测试、故障注入、独立 JVM 崩溃测试
├── docs/DESIGN.md                  完整设计与恢复说明
├── docs/RUN_RESULTS.md             实测结果说明
├── docs/results/                   实际构建、演示、CLI 输出
├── scripts/                        Windows 验证、演示、打包脚本
└── .github/workflows/ci.yml         Windows/Linux CI 配置
```

设计采用同步刷盘和全量压缩，优先保证机试项目的可审查性与恢复正确性。压缩期间其他操作等待；不提供 RESP、MVCC、事务或 HTTP。Windows 上目录 fsync 只能尽力执行，因此测试与保证范围为**进程异常退出恢复**，不宣称对任意硬件断电或磁盘损坏都能无损恢复。完整记录校验失败会明确报错。

## 打包提交

完成构建和 Git 提交后执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/package-submission.ps1 -Name "你的姓名"
```

输出为 `submission/你的姓名.zip`。不传 `-Name` 时生成 `submission/姓名.zip`，可直接改名；覆盖已有同名压缩包需要显式传 `-Force`。

ZIP 包含 **所有 Git 跟踪的工作区文件、完整 `.git` 历史、设计文档、实际运行日志和可执行 JAR**，解压后可继续使用 Git，也可用 IDEA 打开。依赖缓存、演示数据库和其他构建中间文件不打包。整个 `submission/` 被 Git 忽略，避免压缩包递归进入自身。

仓库地址：[nyarlathotepmarlon/TestAnswer](https://github.com/nyarlathotepmarlon/TestAnswer)。实际验证结果见 [RUN_RESULTS.md](docs/RUN_RESULTS.md)。

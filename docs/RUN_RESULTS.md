# 运行验证结果

验证日期：2026-09-29。验证环境为当前 Windows 10 开发机，Java 17、Maven 3.6.1；源码、构建及输出日志均使用 UTF-8。

## 可复现命令

在项目根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
```

脚本依次执行 `mvn -B -ntp clean verify`、实际 JAR 演示以及独立 Java 进程的 CLI 检查，并覆盖更新下方日志。

## 单元和集成测试

| 测试类 | 数量 | 内容 |
| --- | ---: | --- |
| LsmStoreTest | 17 | 读写、范围、压缩、并发、重启、随机模型等 |
| RecoveryTest | 24 | 6 个异常边界、6 次 JVM 强制退出、损坏/尾部恢复等 |
| MainTest | 10 | CLI、Shell、退出码和 JSON |
| BloomFilterTest | 1 | 无假阴性与不存在键的过滤 |
| **合计** | **52** | **Failures: 0, Errors: 0, Skipped: 0** |

原始构建日志：[build-output.txt](results/build-output.txt)。每条 JUnit 用例的机器可读报告在构建后的 `target/surefire-reports/TEST-*.xml`。

## 演示结果

`Demo.main` 与 JAR 的 `demo` 命令运行同一段演示代码。真实输出见 [demo-output.txt](results/demo-output.txt)。

- 先写入张三、李四、王五，随后覆盖张三并删除李四。
- 最后一个包含中文和 emoji 的键仅在 WAL/MemTable 中，关闭后重新打开能够恢复。
- `[user:001, user:004)` 返回张三的新值与王五，不包含已删除的李四，也不包含右边界。
- 压缩前：3 张 SSTable、6 条物理记录、553 字节。
- 压缩后：1 张 SSTable、3 条存活记录、262 字节。
- 再次重新打开，三个存活键保留，删除键没有复活。
- 所有演示断言通过。

示例统计的字节数来自当前格式和这组固定键值，不代表通用性能测试；每次演示使用新的目录。

## 独立进程 CLI 检查

真实输出见 [cli-output.txt](results/cli-output.txt)。脚本针对同一数据目录，每条命令都重新启动 Java 进程，验证：

1. `put` 写入两个 key，`flush` 生成 SSTable。
2. 覆写第一个 key，新进程 `get` 读取到新值。
3. 删除第二个 key，执行 `compact`。
4. 范围扫描只返回第一个 key 的新值。
5. 查询删除键返回 `found:false`，退出码为 3。
6. `stats` 显示一张 SSTable、一个存活物理记录。

本地验证通过不等同于人工打开了 IDEA 界面。已提供标准 Maven 项目及 IDEA 共享运行配置，IDEA 运行步骤见根目录 README；当前验证实际使用同一 Java 17 编译器、JUnit 和 main/JAR 入口。

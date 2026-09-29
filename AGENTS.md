# 项目约定

- 开发系统为 Windows，路径与内容可能包含中文，读取和写入文本均采用 UTF-8。
- 项目使用 Java 17 和 Maven；运行 `mvn clean verify` 验证修改。
- 选择实现机试问题三，存储引擎运行时仅依赖 JDK。
- 修改持久化流程时必须保留 WAL 先写、MANIFEST 原子发布和故障恢复测试。

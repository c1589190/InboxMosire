# 环境

- JDK 21。
- 仓库内 `./mvnw`，Maven 3.9.x。
- AgentLib 本地 Maven 坐标：`io.mosire:agentlib-mosire:0.2.4`（源码最新；公开 Pages 当前仍为 0.2.3，需上游补发布）。
- Jackson BOM / 依赖：`2.22.4`；SLF4J：`2.0.20`；JUnit：`6.1.3`；SQLite JDBC：`3.53.4.0`。
- 默认数据目录：`./data`（运行时；不提交）。
- API/MCP 默认只绑定 `127.0.0.1`。
- 真实密钥只通过本地 CLI 生成，不入库、不入仓。

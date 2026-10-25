# InboxMosire

一个 **Agent 无关** 的本地通知板后台进程：通知存储、claim/complete 状态机、SSE 事件流、Producer HTTP API 和基于 AgentLib 的 MCP 工具面。

它解决的不是“让 Agent 反复 sleep 查通知”，而是把等待从 Agent 回合里搬到宿主适配器：

- Agent 通过 MCP 查看、领取、完成通知；
- 外部程序通过回环 HTTP API 推送通知；
- DSH、Claude、Codex 或其他宿主各自的薄适配器订阅 SSE，在合适的时机唤醒自己的会话；
- 核心不知道任何宿主的 session/followup 语义。

## 模块

| 模块 | 职责 |
|---|---|
| `inbox-core` | 领域类型、状态机、用例与端口；不依赖 AgentLib、HTTP、SQLite |
| `inbox-sqlite` | SQLite WAL、迁移、事务、claim、outbox 事件、API token 存储 |
| `inbox-client` | Agent 无关的 Java HTTP 客户端、TokenStore、BindingDescriptor |
| `inbox-mcp-tools-client` | 程序自有 MCP 使用的 inbox AgentTool 集合 |
| `inbox-server` | Producer/Adapter HTTP API、SSE、AgentLib MCP 工具、CLI 和进程生命周期 |
| `inbox-embedded` | 其他 Java 程序一行启动独立 embedded InboxMosire server |

## 快速开始

```bash
./mvnw -B -ntp verify
java -jar inbox-server/target/inbox-server-0.1.0-SNAPSHOT-all.jar init --data-dir ./data
java -jar inbox-server/target/inbox-server-0.1.0-SNAPSHOT-all.jar token issue \
  --data-dir ./data --label dsh-adapter --scope events:read --scope status:read --inbox main
java -jar inbox-server/target/inbox-server-0.1.0-SNAPSHOT-all.jar serve --data-dir ./data
```

共享客户端：

```xml
<dependency>
  <groupId>io.mosire</groupId>
  <artifactId>inbox-client</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

一行启动 embedded server：

```java
try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start("app-a")) {
  URI api = inbox.apiUrl();
  URI mcp = inbox.mcpUrl();
  String token = inbox.provisionerToken();
}
```

使用流程见 [`docs/upstream/program-integration.md`](docs/upstream/program-integration.md) 和
[`docs/upstream/embedded-server.md`](docs/upstream/embedded-server.md)。

默认只监听回环地址：

- API + SSE：`http://127.0.0.1:18765`
- MCP Streamable HTTP：`http://127.0.0.1:18766/mcp`

## 文档

- [`设计-服务端架构.md`](设计-服务端架构.md)
- [`开发计划.md`](开发计划.md)
- [`开发计划-多程序接入与Token管理.md`](开发计划-多程序接入与Token管理.md)
- [`docs/upstream/http-sse-contract.md`](docs/upstream/http-sse-contract.md)
- [`docs/upstream/mcp-tools.md`](docs/upstream/mcp-tools.md)
- [`docs/upstream/wake-adapter-contract.md`](docs/upstream/wake-adapter-contract.md)
- [`docs/upstream/program-integration.md`](docs/upstream/program-integration.md)
- [`docs/upstream/embedded-server.md`](docs/upstream/embedded-server.md)
- [`docs/upstream/OTHER-PROJECT-INTEGRATION-PROMPT.md`](docs/upstream/OTHER-PROJECT-INTEGRATION-PROMPT.md)
- [`docs/upstream/DSH-MULTI-BINDING-PROMPT.md`](docs/upstream/DSH-MULTI-BINDING-PROMPT.md)
- [`docs/api/openapi.yaml`](docs/api/openapi.yaml)
- [`docs/agents/STATUS.md`](docs/agents/STATUS.md)

## 安全基线

- 事件流和 MCP 面只绑回环地址。
- Producer / Adapter 使用 `Authorization: Bearer`，token 不以任何形式放在 URL。
- token 只存 SHA-256 hash；日志不记录 token、payload 和 claim token。
- SSE 事件不含通知正文、标题、claim token。
- 每个 inbox 有 advisory `occupied/occupiedBy` 标记；它不是独占锁，访问边界仍由 token 控制。
- Agent 操作 MCP 不需要知道宿主 session；claim capability 负责所有权。

## License

Apache License 2.0，见 [`LICENSE`](LICENSE)。

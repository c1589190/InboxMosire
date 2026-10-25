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
| `inbox-server` | Producer HTTP API、SSE、AgentLib MCP 工具、CLI 和进程生命周期 |

## 快速开始

```bash
./mvnw -B -ntp verify
java -jar inbox-server/target/inbox-server-0.1.0-SNAPSHOT-all.jar init --data-dir ./data
java -jar inbox-server/target/inbox-server-0.1.0-SNAPSHOT-all.jar token issue \
  --data-dir ./data --label dsh-adapter --scope events:read --scope status:read --inbox main
java -jar inbox-server/target/inbox-server-0.1.0-SNAPSHOT-all.jar serve --data-dir ./data
```

默认只监听回环地址：

- API + SSE：`http://127.0.0.1:18765`
- MCP Streamable HTTP：`http://127.0.0.1:18766/mcp`

## 文档

- [`设计-服务端架构.md`](设计-服务端架构.md)
- [`开发计划.md`](开发计划.md)
- [`docs/upstream/http-sse-contract.md`](docs/upstream/http-sse-contract.md)
- [`docs/upstream/mcp-tools.md`](docs/upstream/mcp-tools.md)
- [`docs/upstream/wake-adapter-contract.md`](docs/upstream/wake-adapter-contract.md)
- [`docs/agents/STATUS.md`](docs/agents/STATUS.md)

## 安全基线

- 事件流和 MCP 面只绑回环地址。
- Producer / Adapter 使用 `Authorization: Bearer`，token 不以任何形式放在 URL。
- token 只存 SHA-256 hash；日志不记录 token、payload 和 claim token。
- SSE 事件不含通知正文、标题、claim token。
- Agent 操作 MCP 不需要知道宿主 session；claim capability 负责所有权。

## License

Apache License 2.0，见 [`LICENSE`](LICENSE)。

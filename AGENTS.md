# AGENTS.md —— InboxMosire 常驻内核

> 冲突顺序：用户当次明确指令 > 本文件 > 其他工程说明。

## 1. 项目目标与边界

InboxMosire 是 **Agent 无关的本地通知板 daemon**：

- MCP 是 Agent 操作面；
- Producer HTTP + SSE 是宿主适配器/外部生产者面；
- 唤醒是宿主能力，不属于核心后端；
- `InboxMosire-DSH` 只是第一个薄适配器。

依赖方向：

```text
inbox-server → inbox-sqlite → inbox-core
inbox-server → AgentLib (仅 MCP adapter)
```

`inbox-core` 不得依赖 AgentLib、JDK HttpServer、SQLite 或 Jackson。

## 2. 红线

1. SSE 和 followup 唤醒消息不得携带通知正文、标题、payload、claim token、API token。
2. API token 只存 hash；不得进 URL、日志、异常、仓库。
3. Producer 写权限与 Adapter 读事件权限分开；不得用同一个通用 token 扩大权限。
4. MCP 不提供 `wait`/长轮询作为正式主流程。
5. 所有默认监听地址必须是 `127.0.0.1` 或 Unix 本地面。
6. 通知状态和 event outbox 必须在同一 SQLite 事务中提交。
7. 核心不得依赖 DSH，也不得知道 session id、`agent.followup()`。
8. 不把 `.work/`、`.omo/`、`target/`、SQLite 文件、日志提交进仓库。
9. 改持久状态、MCP 工具面、HTTP/SSE 载荷、所有权/生命周期，先改设计文档再改代码。
10. 测试失败时不得通过放宽断言、跳过用例或减少门禁来制造绿色。

## 3. 文档与路由

| 文档 | 内容 |
|---|---|
| `docs/agents/STATUS.md` | 当前状态、最近验证、未完成项 |
| `docs/agents/WORKFLOW.md` | 设计、实现、测试、提交流程 |
| `docs/agents/VERIFICATION.md` | 验证命令与证据要求 |
| `docs/agents/ENVIRONMENT.md` | 本机 JDK/Maven/数据目录事实 |
| `docs/upstream/http-sse-contract.md` | Adapter/Producer HTTP+SSE 契约 |
| `docs/upstream/mcp-tools.md` | Agent MCP 工具契约 |
| `docs/upstream/wake-adapter-contract.md` | 宿主唤醒适配器契约 |
| `设计-服务端架构.md` | 当前架构冻结决策 |
| `开发计划.md` | 分阶段范围与验收判据 |

## 4. 构建与提交

- JDK 21，仓库内 `./mvnw`。
- 完整门禁：`scripts/gate.sh full`。
- 提交按关注点拆分；不 `git add -A` 混入无关文件。
- 不提交数据库、运行日志、真实 token。
- 推送前检查 `git status` 与 `git diff --cached`。

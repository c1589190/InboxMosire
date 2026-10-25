# STATUS

- 日期：2026-10-25
- 分支：`main`
- 版本：`0.1.0-SNAPSHOT`
- 阶段：后端 v1 完成，等待 `InboxMosire-DSH` 适配器接入
- 最近代码提交：`3237040 fix(server): 收紧回环与过期 claim 语义并补 CLI/SSE 测试`

## 已完成

- Java 21 + Maven 三模块：`inbox-core` / `inbox-sqlite` / `inbox-server`。
- SQLite WAL、事务型状态 + event outbox、revision、dedupe、claim lease、过期 sweep。
- Producer HTTP API、Bearer token hash、scope/inbox/channel 限制。
- SSE + Last-Event-ID、CloudEvents 1.0 事件、heartbeat、channel 过滤。
- AgentLib `0.2.4` MCP 工具面：7 个工具，无 `inbox_wait`。
- daemon CLI：`init` / `serve` / `inbox create` / `token issue` / `token revoke` / `doctor`。
- DSH/多 Agent 的 HTTP+SSE+Wake adapter 契约与 OpenAPI 规范。

## 最近验证

命令：

```bash
./mvnw -B -ntp clean verify
```

结果：

- `BUILD SUCCESS`
- Tests run: 21（core 5 / sqlite 9 / server 7），Failures 0, Errors 0, Skipped 0
- Checkstyle：三个模块均 0 violations
- 产物：`inbox-server/target/inbox-server-0.1.0-SNAPSHOT-all.jar`

## 已验证的真实链路

- 官方 MCP Java client 经真实 socket：`initialize -> tools/list -> tools/call`。
- MCP push/list/claim/complete 全流程。
- HTTP Producer push + Adapter status。
- SSE CloudEvents 不包含 payload/title，验证了正文不外泄。
- SSE Last-Event-ID 补发。
- channel-scoped adapter 不会看到其他 channel。
- SQLite 并发 claim 只有一个成功、过期 claim 恢复、重开数据库状态保持、credential hash/revoke。

## 已知限制 / 未验证

- `InboxMosire-DSH` 尚未实现；DSH 的 turn-end/idle/followup 幂等能力仍需在 DSH 仓库实测。
- MCP HTTP 面沿用 AgentLib v1 的无鉴权回环基线；v1 已禁止非回环绑定，但未来远程暴露必须补鉴权。
- 事件 outbox 当前没有保留期/清理策略；长期运行需要后续 retention 设计。
- 没有 Web UI。
- 没有多节点/Postgres/远程公网支持。

## 下一步

1. 把 `docs/api/` 与 `docs/upstream/DSH-HANDOFF.md` 交给 `InboxMosire-DSH`。
2. 在 DSH 插件实现 SSE client、session binding、turn-end 去重和固定唤醒模板。
3. 做真实 DSH idle → push → followup → claim → complete → 继续/等待 的端到端验收。
4. 根据长期运行数据决定 event retention 与 MCP HTTP 鉴权方案。

# API 规范

给 `InboxMosire-DSH` 的直接交接材料：

1. [`http-sse-contract.md`](../upstream/http-sse-contract.md) —— Adapter/Producer HTTP + SSE。
2. [`openapi.yaml`](openapi.yaml) —— 机器可读 OpenAPI 3.1。
3. [`wake-adapter-contract.md`](../upstream/wake-adapter-contract.md) —— DSH 唤醒适配器状态机与固定唤醒模板。
4. [`mcp-tools.md`](../upstream/mcp-tools.md) —— Agent 侧 MCP 工具（DSH 通常不需要直接调用，但排障需要）。
5. [`DSH-HANDOFF.md`](../upstream/DSH-HANDOFF.md) —— 可直接交给 `InboxMosire-DSH` 的实现交接单。
6. [`DSH-MULTI-BINDING-PROMPT.md`](../upstream/DSH-MULTI-BINDING-PROMPT.md) —— 当前 v2 多 session / 多 inbox 对接提示词。
7. [`program-integration.md`](../upstream/program-integration.md) —— 其他程序使用 inbox-client + 自有 MCP 的 Mode A 接入规范。
8. [`OTHER-PROJECT-INTEGRATION-PROMPT.md`](../upstream/OTHER-PROJECT-INTEGRATION-PROMPT.md) —— 可直接交给其他项目 Agent 的接入/创建 inbox 提示词。

推荐的 DSH 实现只需要依赖前 3 份；MCP 工具面由 Agent 运行时使用。

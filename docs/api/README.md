# API 规范

给 `InboxMosire-DSH` 的直接交接材料：

1. [`http-sse-contract.md`](../upstream/http-sse-contract.md) —— Adapter/Producer HTTP + SSE。
2. [`openapi.yaml`](openapi.yaml) —— 机器可读 OpenAPI 3.1。
3. [`wake-adapter-contract.md`](../upstream/wake-adapter-contract.md) —— DSH 唤醒适配器状态机与固定唤醒模板。
4. [`mcp-tools.md`](../upstream/mcp-tools.md) —— Agent 侧 MCP 工具（DSH 通常不需要直接调用，但排障需要）。
5. [`DSH-HANDOFF.md`](../upstream/DSH-HANDOFF.md) —— 可直接交给 `InboxMosire-DSH` 的实现交接单。

推荐的 DSH 实现只需要依赖前 3 份；MCP 工具面由 Agent 运行时使用。

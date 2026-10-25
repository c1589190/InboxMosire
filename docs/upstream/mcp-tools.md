# InboxMosire MCP 工具契约 v1

> Transport：MCP Streamable HTTP
> 默认地址：`http://127.0.0.1:18766/mcp`
> Server name：`inbox-mosire`
> Server version：`0.1.0`

工具实现使用 AgentLib `0.2.4`。MCP 只负责 Agent 操作通知板，不负责唤醒空闲会话。

## 1. 通用约定

- 所有工具返回 JSON object（作为 MCP text content）。
- 成功结果没有 `[mosire:code=...]` 前缀；错误结果是 `[mosire:code=<CODE>]\n<消息>`。
- 默认 inbox 为 `main`；每个工具都接受可选 `inbox`。
- claim token 是 opaque capability，只出现在 claim 结果中。它不绑定 MCP session，也不绑定 DSH session。
- 工具不提供 `inbox_wait`、sleep 或长轮询。
- `payload` 只在 claim 结果中返回。

## 2. 工具列表

| 工具 | 作用 |
|---|---|
| `inbox_status` | revision 与各 channel 计数 |
| `inbox_list` | 列出 available item 的轻量摘要 |
| `inbox_claim` | 原子领取一个或多个 item |
| `inbox_renew` | 续 claim lease |
| `inbox_complete` | 完成/失败归档 claim |
| `inbox_release` | 放回 available |
| `inbox_push` | Agent 自己压入任务 |

## 3. 推荐 Agent 循环

```text
收到宿主唤醒
  -> inbox_list
  -> 选择 0 个或多个 item
  -> inbox_claim(选中的 itemIds)
  -> 逐个处理；必要时 inbox_renew
  -> inbox_complete(claimTokens)
  -> 如果返回 available > 0：
       可以在同一 turn 继续 claim，
       也可以结束 turn，让宿主在 turn end 再唤醒一次
  -> 如果 available = 0：结束 turn
```

选择哪些 item、处理顺序、执行哪些业务工具，全部由 Agent 自己决定。

## 4. 工具明细

### `inbox_status`

入参：

```json
{
  "inbox": "main",
  "channels": ["ticket"]
}
```

返回：

```json
{
  "inbox": "main",
  "revision": 8,
  "available": 2,
  "claimed": 0,
  "channels": {"ticket": {"available": 2, "claimed": 0}},
  "updatedAt": "2026-10-25T00:00:00Z"
}
```

### `inbox_list`

入参：

```json
{
  "inbox": "main",
  "channels": ["ticket"],
  "limit": 50
}
```

返回项不含 payload：

```json
{
  "inbox": "main",
  "count": 1,
  "items": [
    {
      "itemId": "itm_...",
      "channel": "ticket",
      "kind": "reply",
      "title": "工单更新",
      "priority": 0,
      "attempts": 0,
      "createdAt": "2026-10-25T00:00:00Z"
    }
  ]
}
```

### `inbox_claim`

入参：

```json
{
  "inbox": "main",
  "itemIds": ["itm_a", "itm_b"],
  "leaseSeconds": 300
}
```

限制：

- `itemIds`：1..50，重复 id 自动去重。
- `leaseSeconds`：30..3600，默认 300。

返回：

```json
{
  "revision": 9,
  "available": 1,
  "claimed": [
    {
      "itemId": "itm_a",
      "channel": "ticket",
      "kind": "reply",
      "title": "工单更新",
      "priority": 0,
      "claimToken": "clm_...",
      "claimExpiresAt": "2026-10-25T00:05:00Z",
      "payload": {"ticketId": "t-42"}
    }
  ],
  "rejected": [
    {"subject": "itm_b", "code": "ITEM_UNAVAILABLE", "message": "通知已被领取或完成"}
  ]
}
```

逐项处理：能领到的进 `claimed`，不能领到的进 `rejected`。

### `inbox_renew`

入参：

```json
{
  "inbox": "main",
  "claimTokens": ["clm_..."],
  "leaseSeconds": 300
}
```

返回：

```json
{
  "renewed": [
    {"claimToken": "clm_...", "itemId": "itm_a", "claimExpiresAt": "2026-10-25T00:10:00Z"}
  ],
  "rejected": []
}
```

### `inbox_complete`

入参：

```json
{
  "inbox": "main",
  "completions": [
    {"claimToken": "clm_...", "outcome": "completed", "summary": "已回复"},
    {"claimToken": "clm_...", "outcome": "failed", "summary": "目标不可达"}
  ]
}
```

- `outcome`：`completed`（默认）或 `failed`。
- 同一 claim 重复 complete 幂等返回 `idempotent=true`，不重复推进 revision、不重复产生 SSE 事件。
- 返回 `available > 0` 表示还有通知；Agent 可以继续，也可以结束 turn。

返回：

```json
{
  "revision": 10,
  "available": 1,
  "completed": [
    {"itemId": "itm_a", "outcome": "completed", "idempotent": false}
  ],
  "rejected": []
}
```

### `inbox_release`

入参：

```json
{"inbox": "main", "claimTokens": ["clm_..."]}
```

返回：

```json
{
  "revision": 11,
  "available": 2,
  "releasedItemIds": ["itm_a"],
  "rejected": []
}
```

### `inbox_push`

Agent 可以给自己或别的消费者压任务：

```json
{
  "inbox": "main",
  "channel": "self-task",
  "kind": "followup",
  "title": "稍后检查工单 t-42",
  "payload": {"ticketId": "t-42"},
  "dedupeKey": "self:t-42:check",
  "priority": 10
}
```

返回同 Producer push：

```json
{
  "itemId": "itm_...",
  "duplicate": false,
  "revision": 12,
  "createdAt": "2026-10-25T00:00:00Z"
}
```

## 5. 稳定错误码

- `INVALID_ARGUMENT`
- `NOT_FOUND`
- `INTERNAL`
- 逐项拒绝码：`ITEM_NOT_FOUND`, `ITEM_UNAVAILABLE`, `ITEM_NOT_READY`, `CLAIM_INVALID`, `CLAIM_EXPIRED`, `CLAIM_COMPLETED`, `CLAIM_CONFLICT`

MCP SDK 可能在进入工具体前就按 JSON Schema 拒绝请求；这种错误形如：

```text
Tool (inbox_claim) input validation failed: ...
```

这不属于 InboxMosire 的业务错误码，但同样是 fail-closed。

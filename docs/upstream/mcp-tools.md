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

---

# v1.1 增补：程序自有 MCP（Mode A）

共享模块 `inbox-mcp-tools-client` 可以把 `InboxClient` 包装成一组 AgentLib `AgentTool`，注册到程序自己的 MCP server：

```java
InboxClient programClient = InboxClient.forTokenFile(baseUrl, tokenFile);
ToolRegistry registry = new ToolRegistry();
registry.registerAll(new InboxClientMcpTools(programClient, "app-a").all());
```

工具：

- `inbox_status`
- `inbox_list`
- `inbox_claim`
- `inbox_renew`
- `inbox_complete`
- `inbox_release`
- `inbox_push`
- `inbox_occupancy`
- `inbox_occupy`
- `inbox_vacate`
- `inbox_credential_info`

与中心 MCP 工具的区别：

- 工具内部通过 HTTP + `InboxClient` 调 daemon；
- 可被程序自己的 MCP server 和程序业务工具放在一起；
- token 留在程序进程/secret source，不进入工具参数；
- `inbox_credential_info` 只返回 metadata/fingerprint，不返回 raw token；
- `inbox_occupancy` / `inbox_occupy` / `inbox_vacate` 只维护 advisory 占用标记；不阻塞其他 token 持有者；
- claim token 仍会出现在 claim 结果中，因为它是 Agent 处理对应 item 的能力凭证。

目标会话在 Mode A 下只调用程序自有 MCP；DSH adapter 只负责 SSE + wake。

---

# v1.3 增补：通用 Item、Summary/Content 与修改工具

每个 item 的核心人类可读字段：

```text
summary : 短摘要
content : 不透明完整内容（文本/Markdown/JSON 字符串均可）
```

`inbox_list` 只返回 summary；`inbox_view` 返回 summary + content；`inbox_claim` 进入处理中并返回 content。`payload/title/payloadRef` 是旧兼容别名。

## 新增中心 MCP 工具

| 工具 | 作用 | 需要 |
|---|---|---|
| `inbox_view` | 查看单条 summary + content，不 claim | items:read |
| `inbox_update` | 替换 summary/content/contentRef/kind/priority | items:manage |
| `inbox_append` | 追加 content 文本，可选更新 summary/priority | items:manage |
| `inbox_delete` | 归档/删除 available item | items:manage |

### `inbox_view`

```json
{"inbox":"main","itemId":"itm_..."}
```

返回 item 完整视图，content 是程序/用户自定义的不透明内容。

### `inbox_update`

```json
{
  "inbox":"main",
  "itemId":"itm_...",
  "summary":"新的摘要",
  "content":"新的完整内容",
  "priority":100,
  "expectedRevision":3
}
```

只允许 available item；claimed item 返回 `CONFLICT`。

### `inbox_append`

```json
{
  "inbox":"main",
  "itemId":"itm_...",
  "content":"追加的一段内容",
  "summary":"可选新摘要",
  "priority":100,
  "expectedRevision":3
}
```

append 使用换行连接旧 content。结构化内容需要程序自己序列化。

### `inbox_delete`

```json
{"inbox":"main","itemId":"itm_...","expectedRevision":3}
```

归档为 done，返回 `deleted=true`；不会物理删除审计记录。

## 当前工具数量

- 中心后端 MCP：11 个（原 7 个 + view/update/append/delete）
- 程序自有 `inbox-mcp-tools-client`：15 个（中心 11 个 + occupancy 3 个 + credential_info 1 个）

## 兼容和边界

- `inbox_push` 接受 `summary/content`，也接受旧 `title/payload`。
- `inbox_list` 返回 `summary`，并保留 `title` 兼容字段。
- `inbox_claim` 返回 `summary/content`；旧 `payload` 字段不再保证。
- update/append/delete 只允许 `available` item；claimed item 返回 409。
- 修改会推进 revision 并产生 event，下一次 wake 摘要会更新。
- content 不会被核心解释为链接或自动工具调用。

## v1.5 Item 冲突错误

`inbox_update` / `inbox_append` / `inbox_delete` 的 409 现在区分：

```text
ITEM_STATUS_CONFLICT
ITEM_REVISION_CONFLICT
```

MCP 工具结果仍用 code + message；message 会附安全 details。程序自有客户端应保留
`InboxClientException.details()`，Bot/程序包装层不要把所有 409 都压成同一个
`INBOX_CONFLICT`。

- claimed: `itemStatus=claimed`, `allowedActions=[complete,release]`
- done: `itemStatus=done`, `allowedActions=[]`
- stale available: `expectedRevision`, `actualRevision`, `itemStatus=available`

## v1.7 restSeconds（DSH adapter hint）

`inbox_complete` 和 `inbox_delete` 的 MCP schema 接受可选：

```json
{"restSeconds": 60}
```

Inbox core 不解释这个字段，也不改变 item 状态；它只是让拥有该目标 session 的
adapter 在成功完成后设置 session 级休息。`0` 表示清除休息，最大 `86400` 秒。

- 休息是 adapter binding 状态，不是 Inbox 板级静音；
- 休息期间 item 仍保持原状态；
- DSH adapter 在休息期间不拉 status/summary，也不 followup；
- 到期后按最新 revision 最多补一次 wake；
- Bot 的 `bot_thread_ack` 需要单独增加同名可选参数，才能让 Bot 流程携带休息。

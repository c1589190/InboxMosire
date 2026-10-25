# InboxMosire Producer / Adapter HTTP + SSE 契约 v1

> 状态：v1 冻结。机器可读版本见 [`docs/api/openapi.yaml`](../api/openapi.yaml)。
> 默认只绑 `127.0.0.1`。所有 token 通过 `Authorization: Bearer <token>` 传递，禁止放 URL。

## 1. 两个客户端角色

| 角色 | Scope | 能力 |
|---|---|---|
| Producer | `items:write` | 向指定 inbox/channel push 通知 |
| Adapter | `status:read`, `events:read` | 读计数、订阅 SSE；不能 push/claim/complete |

一个 credential 可以绑定一个 inbox（`*` 表示任意）和一组 channel（空集表示全部）。
签发：

```bash
java -jar inboxd.jar token issue \
  --data-dir ./data \
  --label dsh-adapter \
  --scope status:read \
  --scope events:read \
  --inbox main \
  --channel ticket \
  --channel tweet-review
```

token 原文只显示一次，数据库只保存 SHA-256 hash。

## 2. 端点

### `GET /healthz`

无鉴权。

```json
{"status":"ok"}
```

### `GET /readyz`

无鉴权。500 之外不可用用 503。

### `POST /v1/inboxes/{inbox}/items`

需要 `items:write`，且 token 必须允许该 inbox 与请求的 channel。

请求：

```http
POST /v1/inboxes/main/items
Authorization: Bearer <producer-token>
Content-Type: application/json

{
  "channel": "ticket",
  "kind": "reply",
  "title": "工单更新",
  "payload": {"ticketId": "t-42", "body": "正文只允许在 claim 后返回"},
  "payloadRef": null,
  "dedupeKey": "ticket:t-42:reply:7",
  "priority": 0
}
```

字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `channel` | 是 | `[A-Za-z0-9._-]{1,64}` |
| `kind` | 否 | 默认 `notification` |
| `title` | 否 | 最长 512；不在 SSE/wake 中出现 |
| `payload` | 否 | 任意 JSON 值；claim 后才返回 |
| `payloadRef` | 否 | 外部正文引用，最长 2048 |
| `dedupeKey` | 否 | 同 inbox+channel 的活跃通知去重 |
| `priority` | 否 | -1000..1000，默认 0 |

响应：

- 新 item：`201`
- 命中 dedupe：`200`，`duplicate=true`

```json
{
  "itemId": "itm_...",
  "duplicate": false,
  "revision": 7,
  "createdAt": "2026-10-25T00:00:00Z"
}
```

### `GET /v1/inboxes/{inbox}/status`

需要 `status:read`。

查询参数：

- `channel=ticket&channel=tweet-review`
- 服务端也接受 `channel=ticket,tweet-review`

响应：

```json
{
  "inbox": "main",
  "revision": 7,
  "available": 2,
  "claimed": 1,
  "channels": {
    "ticket": {"available": 2, "claimed": 0},
    "tweet-review": {"available": 0, "claimed": 1}
  },
  "updatedAt": "2026-10-25T00:00:00Z"
}
```

channel-scoped token 只能看到自己可访问的 channel；响应中的 `available/claimed` 是过滤后的合计。

### `GET /v1/inboxes/{inbox}/events`

需要 `events:read` 与 `Accept: text/event-stream`。

```http
GET /v1/inboxes/main/events
Authorization: Bearer <adapter-token>
Accept: text/event-stream
Last-Event-ID: 128
```

#### SSE 帧

```text
id: 129
event: inbox.changed
data: {"specversion":"1.0","id":"evt_...","source":"urn:inboxmosire:inbox:main","type":"io.mosire.inbox.changed.v1","subject":"main","time":"2026-10-25T00:00:00Z","datacontenttype":"application/json","data":{"inbox":"main","revision":8,"available":1,"claimed":0,"channels":{"ticket":{"available":1,"claimed":0}},"changedChannels":["ticket"]}}

```

心跳：

```text
: ping

```

#### cursor 语义

- SSE `id:` 是持久化 event seq，单调递增。
- 带 `Last-Event-ID: N` 重连时，从 `seq > N` 开始补发。
- 不带 `Last-Event-ID` 时，从当前 tip 开始，不重放历史事件。
- 事件只是 invalidation。适配器收到事件后必须调用 `status` 取最新真相。
- 适配器连接成功后先调用一次 `status`；这可以在没有事件重放时补上当前积压。
- 如果 token 限制 channel，事件只包含允许 channel 的统计与 `changedChannels`；没有允许 channel 变化的帧不会发送。

#### 隐私边界

SSE 事件不得包含：

- item `title`
- item `payload`
- `payloadRef`
- claim token
- API token
- 玩家/工单/推文正文

## 3. 错误响应

统一：

```json
{
  "error": {
    "code": "FORBIDDEN",
    "message": "token 缺少 scope: events:read"
  }
}
```

HTTP 状态：

| HTTP | code 示例 |
|---|---|
| 400 | `INVALID_JSON`, `INVALID_CURSOR` |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN` |
| 404 | `NOT_FOUND` |
| 405 | `METHOD_NOT_ALLOWED` |
| 406 | `NOT_ACCEPTABLE` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 422 | `INVALID_ARGUMENT` |
| 500 | `INTERNAL` |

## 4. 环境变量建议

适配器可从本机 secret/config 读取：

```text
INBOX_API_URL=http://127.0.0.1:18765
INBOX_INBOX_ID=main
INBOX_API_TOKEN=<adapter-token>
INBOX_CHANNELS=ticket,tweet-review
INBOX_SSE_HEARTBEAT_SECONDS=15
```

`INBOX_API_TOKEN` 不能进仓库、不能进日志、不能进 URL。

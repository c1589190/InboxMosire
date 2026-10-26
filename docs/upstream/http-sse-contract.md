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

---

# v1.1 增补：多程序 provisioning、操作面与 token 管理

## 1. Scope 全集

| Scope | 作用 |
|---|---|
| `items:write` | push |
| `items:read` | list available item 摘要 |
| `items:manage` | claim / renew / complete / release |
| `status:read` | board status |
| `events:read` | SSE |
| `inbox:ensure` | 幂等创建自己精确绑定的 inbox |
| `credentials:issue:self` | 为自己精确绑定的 inbox 签发不扩权的子 credential |
| `admin:inboxes` | 管理任意 inbox |
| `admin:credentials` | 管理任意 credential |

普通 producer/wake token 不应获得 admin scope。

## 2. Inbox ensure

```http
PUT /v1/inboxes/{inbox}
Authorization: Bearer <inbox:ensure-or-admin-token>
```

响应：

```json
{"inbox":"app-a","created":true}
```

再次调用返回 200 + `created:false`。

`inbox:ensure` 只有在 token 的 inbox 精确等于路径 inbox 时生效；通配 token 不能借 ensure 创建任意 inbox。

## 3. Item 读与 claim 操作面

### `GET /v1/inboxes/{inbox}/items`

需要 `items:read`。

Adapter 在唤醒前可用该端点取 bounded 摘要（默认建议 `limit=3`）；响应不含 payload。DSH wake token 可包含 `items:read`，但仍不得用于 claim/complete（需 `items:manage`）。

```http
GET /v1/inboxes/app-a/items?channel=ticket&limit=50
```

响应：

```json
{
  "inbox":"app-a",
  "count":1,
  "items":[
    {
      "itemId":"itm_...",
      "channel":"ticket",
      "kind":"reply",
      "title":"工单更新",
      "priority":0,
      "attempts":0,
      "createdAt":"2026-10-25T00:00:00Z"
    }
  ]
}
```

### `POST /v1/inboxes/{inbox}/claims`

需要 `items:manage`。

```json
{"itemIds":["itm_a","itm_b"],"leaseSeconds":300}
```

返回 `{revision, available, claimed[], rejected[]}`。`claimed[]` 中每个 item 含 `claimToken` 与 payload；`rejected[]` 含 `subject/code/message`。

### `POST /v1/inboxes/{inbox}/claims/renew`

```json
{"claimTokens":["clm_..."],"leaseSeconds":300}
```

### `POST /v1/inboxes/{inbox}/claims/complete`

```json
{
  "completions":[
    {"claimToken":"clm_...","outcome":"completed","summary":"已处理"}
  ]
}
```

### `POST /v1/inboxes/{inbox}/claims/release`

```json
{"claimTokens":["clm_..."]}
```

channel-scoped `items:read` / `items:manage` token 只能看到并 claim 自己 channel 范围内的 item；越界的 claim 进入 `rejected`。

## 4. Credential 管理

### `POST /v1/admin/inboxes/{inbox}/credentials`

需要 `admin:credentials`，或 `credentials:issue:self` + 精确 inbox 绑定 + 不扩权的 scopes/channels。

```json
{
  "label":"program-a-mcp",
  "scopes":["items:write","items:read","items:manage","status:read"],
  "channels":[],
  "expiresInSeconds":86400
}
```

响应是唯一一次包含 raw token：

```json
{
  "credential":{
    "id":"cred_...",
    "label":"program-a-mcp",
    "scopes":["items:write","items:read","items:manage","status:read"],
    "inbox":"app-a",
    "channels":[],
    "fingerprint":"sha256:...",
    "createdAt":"...",
    "expiresAt":"...",
    "lastUsedAt":null,
    "revokedAt":null,
    "rotatedFrom":null,
    "createdBy":"cred_..."
  },
  "token":"inbox_..."
}
```

### `GET /v1/admin/credentials?inbox=...`

需要 `admin:credentials`。只返回 metadata，不返回 token 原文和 token hash。

### `POST /v1/admin/credentials/{id}/rotate`

撤销旧 credential，签发同 scope 的新 credential，raw token 返回一次。

### `POST /v1/admin/credentials/{id}/revoke`

立即 revoke；旧 token 后续请求 401。

### `GET /v1/admin/whoami`

任何合法 token 可调用，返回自己的 metadata/fingerprint，不返回 raw token。

## 5. Token 使用边界

- raw token 只在 issue/rotate 时返回一次；
- 程序应立刻写入本机 `0600` secret；
- MCP tool result 不返回 raw token；
- SSE/wake/日志不得包含 token；
- credential rotation 后旧 token 立即失效；
- admin credential 不自动适用于普通程序 MCP proxy。

---

# v1.2 增补：Advisory Inbox Occupancy

占用是**提示状态**，不是访问锁。token scope 仍是唯一访问边界；任何持有对应 scope/token 的调用方仍可继续访问 inbox。

## Scope

新增：

```text
inbox:occupy
```

- GET occupancy 使用 `status:read`
- PUT/DELETE occupancy 使用 `inbox:occupy`

## 查询

```http
GET /v1/inboxes/{inbox}/occupancy
Authorization: Bearer <status-read-token>
```

```json
{
  "inboxId": "app-a",
  "occupied": true,
  "occupiedBy": "dsh-session-S1",
  "occupiedAt": "2026-10-25T00:00:00Z",
  "updatedAt": "2026-10-25T00:00:00Z"
}
```

## 标记占用

```http
PUT /v1/inboxes/{inbox}/occupancy
Authorization: Bearer <inbox-occupy-token>
Content-Type: application/json

{"occupiedBy":"dsh-session-S1"}
```

幂等：已占用时保留第一个 occupant，不覆盖；用于展示。

## 清除占用

```http
DELETE /v1/inboxes/{inbox}/occupancy
Authorization: Bearer <inbox-occupy-token>
```

## status 集成

`GET /v1/inboxes/{inbox}/status` 额外返回：

```json
{
  "occupied": true,
  "occupiedBy": "dsh-session-S1",
  "occupiedAt": "..."
}
```

## 生命周期

- daemon 启动时清空所有 occupancy，因为占用表示“当前活着的连接/会话”；
- occupancy 变化不推进 revision、不产生 SSE 事件；
- DSH adapter 在 bind 成功后 PUT occupancy，在 unbind/session-stop 后 DELETE occupancy；
- 其他调用方不会因为 occupied=true 被服务端拒绝；是否尊重占用由调用方/上层策略决定。

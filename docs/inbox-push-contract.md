# Inbox 推送契约（2026-11-06）

本文件是 InboxMosire 仓内契约，抽取自跨仓稿 `docs/superpowers/specs/2026-11-06-inbox-push-contract.md` 的 Inbox 部分。跨仓稿中的宿主侧（DSH/Hermes）行为不在本仓范围内。

## 1. 目标

在保留 `io.mosire.inbox.changed.v1` 失效信号的同时，新增一条**档位化、可 ack** 的推送通道：

- 重要通知低延迟推送；
- 普通通知在一个合并窗口内聚合成一条 delivery；
- 每条 delivery 有稳定 `deliveryId`，接收方 ack 后关闭其覆盖的 item；
- 未 ack 的 delivery 周期性重推，达到上限后标记 `undelivered`，但 item 不丢失。

## 2. content 规则（W1-1）

- `content` 必须是**单个已渲染好的 UTF-8 字符串**。
- 服务端不解析、不改写、不执行 content；Markdown、纯文本、序列化后的 JSON 文本都可以是字符串。
- JSON 对象/数组/数字/布尔作为 `content` 或兼容别名 `payload` 提交时，返回 `422 INVALID_ARGUMENT`，错误信息为：`content 必须是已渲染好的单个字符串`。
- 服务端**不得**静默 `JSON.stringify`/Jackson 序列化或 `toString`。
- 老字段 `title`/`payload`/`payloadRef` 仍是兼容别名；`payload` 同样只接受字符串。

## 3. 档位与合并（W1-5）

| 配置键（env 去掉 `INBOX_` 前缀并小写） | 默认值 | 含义 |
|---|---|---|
| `INBOX_NOTIFY_THRESHOLD` / `--notify-threshold` | `200` | `priority >= threshold` 为重要；阈值范围 `-1000..1000` |
| `INBOX_IMPORTANT_DELAY_MILLIS` | `1000` | 重要 item 从 `available_at` 起的首推等待 |
| `INBOX_NORMAL_DELAY_MILLIS` | `30000` | 普通 item 的聚合窗口 |
| `INBOX_DELIVERY_TTL_MILLIS` | `600000` | 未 ack delivery 的重推基准；第 n 次重推在 `created_at + ttl * attempts` |
| `INBOX_DELIVERY_MAX_ATTEMPTS` | `3` | 最多推送次数（含首推） |
| `INBOX_DELIVERY_SWEEP_INTERVAL_MILLIS` | `500` | 守护线程扫描周期 |

规则：

- 同一 inbox 中所有已到推送时间的 item 合并成**一条** delivery；
- `text` = 各 item `content` 用 `"\n\n"` 连接（`null` content 视为空串）；
- `attachments` = 各 item `content_ref`/`payload_ref` 的并集，元素为 `{name, ref}`，`name` 取 ref 最后一段；
- `important` = 合并项中任意一项重要；
- 未 ack 且 `attempts >= maxAttempts` 时 `state='undelivered'`，item 保持原状态，不删除；
- 已推送且已覆盖的 item 不会被第二条 delivery 重复覆盖。

## 4. 存储（W1-2）

迁移版本：`schema_migration.version = 2`。旧库打开时自动 `CREATE TABLE IF NOT EXISTS` 并用 `ALTER TABLE` 补齐 `inbox_event` 的新列。

```sql
CREATE TABLE inbox_delivery (
  delivery_id TEXT PRIMARY KEY,
  inbox_id    TEXT NOT NULL REFERENCES inbox(id) ON DELETE CASCADE,
  item_ids    TEXT NOT NULL,              -- 逗号分隔 item id
  important   INTEGER NOT NULL,
  text        TEXT NOT NULL,
  attachments TEXT,                       -- JSON [{name,ref}]
  created_at  TEXT NOT NULL,
  acked_at    TEXT,
  attempts    INTEGER NOT NULL DEFAULT 1,
  state       TEXT NOT NULL               -- delivered | acked | undelivered
);
CREATE INDEX idx_delivery_inbox_state ON inbox_delivery(inbox_id, state);
```

`inbox_event` 新增列：

- `event_type TEXT NOT NULL DEFAULT 'io.mosire.inbox.changed.v1'`
- `delivery_id TEXT`
- `data_json TEXT`

事件与 delivery 在同一 SQLite 事务写入，outbox 仍是 SSE 的唯一事实源。

## 5. SSE 事件（W1-3）

SSE `id:` 仍是 durable event sequence。事件名：

- `inbox.changed` → `io.mosire.inbox.changed.v1`（原样保留）；
- `inbox.push` → `io.mosire.inbox.push.v1`（新增）。

changed 与 push 会同时/先后出现；旧消费方只看 `inbox.changed` 不受影响。

push data 字段固定为：

```json
{
  "inbox": "main",
  "deliveryId": "dlv_...",
  "itemIds": ["itm_..."],
  "text": "Rendered text\n\nSecond item",
  "attachments": [{"name": "report.pdf", "ref": "asset:report-1"}],
  "important": true,
  "remaining": 1,
  "revision": 4
}
```

完整 CloudEvents 1.0 包封示例：

```text
id: 13
event: inbox.push
data: {"specversion":"1.0","id":"evt_...","source":"urn:inboxmosire:inbox:main","type":"io.mosire.inbox.push.v1","subject":"main","time":"2026-11-06T00:00:01Z","datacontenttype":"application/json","data":{"inbox":"main","deliveryId":"dlv_...","itemIds":["itm_..."],"text":"Rendered text\n\nSecond item","attachments":[],"important":true,"remaining":1,"revision":4}}
```

约束：

- 单条事件建议 ≤ 64 KiB；
- push 正文只含已渲染字符串，不含 itemId/priority/source 等元数据；`itemIds` 只作为 ack 路由元数据并列返回；
- 不含 API token、claim token；
- 受限 channel token 只有在 delivery 覆盖的所有 item channel 都在其范围内时才收到该 push（防止合并正文跨 channel 泄露）。

## 6. ack 路由（W1-4）

`POST /v1/inboxes/{inbox}/deliveries/{deliveryId}/ack`

- scope：`items:manage`（与 claim/complete 一致）；
- 无请求体；
- 首次 ack：把 delivery 覆盖且仍为 `available` 的 item 置 `done`，写 `completed_at`，delivery 置 `state='acked'`、`acked_at=now`；
- 已 `claimed` 的 item 不动，交由 claim/complete 生命周期处理；
- 幂等：重复 ack 返回 200、`acked=false`、`closedItems=[]` 与当前计数，不报错。

响应：

```json
{
  "deliveryId": "dlv_...",
  "acked": true,
  "closedItems": ["itm_..."],
  "remaining": 0,
  "remainingImportant": 0
}
```

`remaining = available + claimed`；`remainingImportant` 为其中 `priority >= notifyThreshold` 的数量。

## 7. MCP

新增第 13 个工具 `inbox_ack`：

```json
{"inbox": "main", "deliveryId": "dlv_..."}
```

返回与 HTTP ack 相同的五个字段。`inbox` 可省略，默认服务端 defaultInbox。

## 8. 兼容性与错误码

- `GET /items` 仍不返回 content；
- `inbox.changed` 字段原样，仅新增可选的新事件类型；
- 旧客户端继续走 `/items` + `claims` 不受影响；
- 非字符串 content：`422 INVALID_ARGUMENT`；
- delivery 不存在：`404 NOT_FOUND`；
- 无 `items:manage`：`403 FORBIDDEN`；
- 重复 ack：`200`，不是冲突。

## 9. 本仓测试覆盖

- `SqliteInboxStoreTest`：合并/档位/重推/undelivered/ack 幂等/旧库迁移到 v2；
- `InboxHttpServerTest`：非字符串 422、push SSE 帧、ack 权限与幂等；
- `InboxMcpServerTest`：13 个工具、`inbox_ack` 闭环；
- `InboxClientAndMcpToolsTest`：客户端拒绝非字符串 content。

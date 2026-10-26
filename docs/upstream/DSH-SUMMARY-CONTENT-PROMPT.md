# 给 DSH 的下一阶段对接提示词：Summary / Content 通用 Item

你已经在 `InboxMosire-DSH` 完成 wake summary（commit `8c2bdaa`）。
InboxMosire 后端现在把 notification item 统一成：

```text
summary : 短摘要，列表和 wake 摘要使用
content : 不透明完整内容，只有程序 MCP 的 inbox_view/inbox_claim 返回
```

请把 DSH 适配器升级到新字段。**不要实现跨工具自动跳转**；content 是普通数据，不是 DSH 工具调用指令。

## 1. 后端变化

`GET /v1/inboxes/{inbox}/items?limit=N` 的 item 现在包含：

```json
{
  "itemId": "itm_...",
  "channel": "ticket",
  "kind": "reply",
  "summary": "工单更新",
  "title": "工单更新",
  "priority": 10,
  "attempts": 0,
  "createdAt": "..."
}
```

- `summary` 是正式字段；
- `title` 只是 legacy alias，内容与 summary 相同；
- `content` 不在列表里；
- content 不会进入 SSE 或 wake。

## 2. DSH 必改

### 2.1 `lib/inbox-client.js`

`normalizeItems` 改为：

```js
const summary = normalizeText(item.summary ?? item.title)
```

- 优先 `summary`；
- 旧后端没有 summary 时 fallback 到 `title`；
- 仍然只保留：

```text
itemId
channel
kind
summary
```

- 不请求、不保存、不输出 content/payload/payloadRef。

### 2.2 `lib/template.js`

wake 摘要每一行使用：

```json
{"itemId":"itm_...","channel":"ticket","kind":"reply","summary":"..."}
```

不要使用 `title` 作为新字段；兼容输入可以 fallback。

摘要模板继续：

```text
摘要（最多 N 条；可能已变化，claim 时以通知板为准）:
- {...}
- 还有 R 条未展示

请直接通过你的程序 MCP 调用 inbox_claim 选择本回合要处理的 itemId；
如果摘要不足，再调用 inbox_list。
如果当前没有可处理项，结束本回合，不要轮询。
```

### 2.3 不改变

- 同一 revision 最多一次 followup；
- summary 失败降级为无摘要唤醒；
- 401/403 的 SUMMARY_FORBIDDEN 逻辑；
- adapter 不 claim、不 complete、不 push；
- 不新增 DSH 工具；
- 不做 `tools/post-execute` 自动调用、content 链接或工具解析。

## 3. 程序 MCP 的新能力

目标 Agent 使用的程序自有 MCP（`io.mosire:inbox-mcp-tools-client`）现在是 15 个工具，新增：

```text
inbox_view
inbox_update
inbox_append
inbox_delete
```

语义：

- `inbox_view`：读取单条 summary + content，不 claim；
- `inbox_update`：替换 summary/content/contentRef/kind/priority；
- `inbox_append`：追加 content 文本；
- `inbox_delete`：归档 available item；
- update/append/delete 对 claimed item 返回 409 CONFLICT；
- 修改推进 revision，下一次 DSH 摘要唤醒应看到新 summary；
- 如果目标程序要调用 update/append/delete，它的 program MCP token 必须包含 `items:manage`；只有 `items:read` 的 wake token 不能修改。

这些是**程序 MCP**的工具，不是 DSH adapter 的工具。DSH 不需要把它们暴露给宿主；目标 Agent 通过程序自己的 MCP 使用。

## 4. 验收

1. 新 item 只有 `summary`、无 `title`：wake 摘要出现 summary。
2. 旧 item 只有 `title`、无 `summary`：fallback 正常。
3. 用 program MCP `inbox_update` 修改 summary 后，新 revision 的 wake 摘要显示新 summary。
4. wake message、DSH state、日志中没有 content/payload/token。
5. 同一 revision 不重复 followup。
6. summary 接口失败仍能无摘要唤醒。
7. 现有双 binding S1→app-a、S2→app-b 不串线。
8. adapter 仍不 claim/complete/push。

## 5. 交付报告

请报告：

- 改动文件；
- 真实测试命令和计数；
- summary-only 与 legacy title fallback 的证据；
- update summary 后下一次 wake 的证据；
- 隐私扫描结果；
- commit hash 和 push 结果。

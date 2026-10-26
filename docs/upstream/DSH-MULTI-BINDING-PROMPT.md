# 给 InboxMosire-DSH 的 v2 对接提示词

把下面整段交给负责 `/home/cna/InboxMosire-DSH` 的工程 Agent。

---

你是负责 `InboxMosire-DSH` 的工程 Agent。现在要把现有的单 binding adapter 升级成 **单 DSH host 多 session、多 inbox、按需绑定** 的 v2 adapter。

## 1. 冻结边界

- 后端：`/home/cna/InboxMosire`
- DSH adapter：`/home/cna/InboxMosire-DSH`
- 后端是唯一通知状态源；DSH adapter 只做：
  1. 读取 BindingDescriptor；
  2. 按 session 建立独立 SSE；
  3. 拉 status；
  4. idle / turn-end 时 followup；
  5. 持久化每个 binding 的 cursor/revision；
  6. archive/dispose 清理。
- 目标会话的 inbox 操作走**程序自己的 MCP**（Mode A），不直连 InboxMosire MCP。
- DSH adapter 不 claim、不 complete、不 push、不读 item 内容。
- raw token 不进入日志、session transcript 或 adapter state；只允许 tokenRef。

## 2. 必读契约

1. `/home/cna/InboxMosire/docs/upstream/program-integration.md`
2. `/home/cna/InboxMosire/docs/upstream/wake-adapter-contract.md`
3. `/home/cna/InboxMosire/docs/upstream/http-sse-contract.md`
4. `/home/cna/InboxMosire/docs/api/openapi.yaml`
5. `/home/cna/InboxMosire/docs/upstream/mcp-tools.md`

## 3. 目标状态模型

把原来的单个 binding state 升级为：

```json
{
  "version": 2,
  "bindings": {
    "session-S1": {
      "inboxId": "app-a",
      "apiBaseUrl": "http://127.0.0.1:18765",
      "adapterTokenRef": "file:/home/.../s1-wake.token",
      "channels": [],
      "lastEventId": 128,
      "lastObservedRevision": 42,
      "lastWokenRevision": 40,
      "lastWake": null,
      "enabled": true
    },
    "session-S2": {
      "inboxId": "app-b",
      "apiBaseUrl": "http://127.0.0.1:18765",
      "adapterTokenRef": "file:/home/.../s2-wake.token",
      "channels": [],
      "lastEventId": 61,
      "lastObservedRevision": 18,
      "lastWokenRevision": 17,
      "lastWake": null,
      "enabled": true
    }
  }
}
```

要求：

- `bindings` 的 key 是权威 DSH session id；
- 一个 session 当前只绑定一个 inbox；再次 bind 替换该 session 的 binding；
- 不同 session 可绑定不同 inbox；
- 状态文件 v1 自动迁移到 v2；
- 每个 binding 独立 SSE、cursor、revision watermark、wake 去重；
- bind 成功后调用 `PUT /v1/inboxes/{inboxId}/occupancy`，body `{"occupiedBy":"<sessionRef>"}`；
- unbind/session-stop/dispose 时调用 `DELETE /v1/inboxes/{inboxId}/occupancy`；
- occupancy 只是 advisory 标记，不是访问锁；不能用它拒绝其他合法 token 持有者；
- adapter wake token 需要 scope：`status:read`、`events:read`、`items:read`、`inbox:occupy`；
- 旧 state 字段不允许让两个 session 串线。

## 4. BindingDescriptor

程序自己的 MCP 会向目标会话返回：

```json
{
  "inboxId": "app-a",
  "apiBaseUrl": "http://127.0.0.1:18765",
  "adapterTokenRef": "file:/home/cna/InboxMosire/.work/live/secrets/dsh-app-a.token",
  "channels": [],
  "label": "dsh-session-S1"
}
```

DSH 侧必须：

- 校验 `inboxId`；
- 校验 `adapterTokenRef` 是绝对 file URI/path；
- 读取 `0600` token 文件；
- 禁止把 token 原文写进 state/log/transcript；
- 同一 session 重绑时关闭旧 SSE、清旧 timers、保留/重置该 session 的 watermark 按契约执行。

## 5. bind / unbind 工具

现有 `/inbox bind [sessionId]` 不够。升级为：

```text
inbox_adapter_bind({
  sessionId?: string,          // 默认调用者 session
  inboxId: string,             // 必填
  adapterTokenRef: string,     // file:... 绝对引用
  channels?: string[]
})

inbox_adapter_unbind({ sessionId?: string })
inbox_adapter_status({})       // 返回当前 caller 的 binding 和其他 binding 的摘要
```

行为：

- bind 只影响目标 session；
- unbind 只停止目标 session，不影响其他 bindings；
- session archived/disposed/stop -> 立即停止该 binding 并 fail closed；
- 不允许未知 session 被 wake；
- 不允许一个 session 静默接收另一个 inbox 的事件。

## 6. 启动/恢复

1. 加载 v2 state（必要时从 v1 迁移）；
2. 对每个 enabled binding 校验 session 是否 live；
3. 对每个 binding 先 GET `/v1/inboxes/{inboxId}/status`；
4. 若 idle 且 available > 0 且 revision > lastWokenRevision，则先 GET `/v1/inboxes/{inboxId}/items?limit=<N>` 取摘要，再最多 followup 一次；
5. 再按各自 cursor 打开 SSE；
6. 没有 cursor 时从当前 tip 开始，所以必须先 status；
7. daemon 重启/网络恢复后不得把 cursor 串给另一个 binding。

## 7. Wake 规则

每个 binding 独立：

```text
archived/unknown session       -> no delivery
active turn                    -> dirty only
idle && available > 0          -> at most one followup
turn end                       -> re-read status, at most one followup
available = 0                  -> idle
```

- 同一 session 同一 revision 不重复 wake；
- burst debounce 200–500ms；
- 每个 binding 最多一个未完成 wake；
- 无进展退避；
- 401/403/404 停止该 binding 的自动唤醒并显示 configError；
- token rotate 后通过重新 bind/retry 读取新 tokenRef。

## 8. 摘要唤醒模板

DSH adapter 在 followup 前必须完成：

```text
adapterTokenRef -> status -> (available > 0) -> items summary -> followup
```

摘要请求：

```http
GET /v1/inboxes/{inboxId}/items?limit=3
Authorization: Bearer <adapterTokenRef 文件内容>
Accept: application/json
```

摘要只取：

```text
itemId
channel
kind
summary   // legacy fallback: title
```

模板：

```text
[INBOX_HOST_EVENT]
inbox={inboxId} revision={revision} available={available} channels=[{changedChannels}]

摘要（最多 {N} 条；可能已变化，claim 时以通知板为准）:
- {"itemId":"itm_...","channel":"ticket","kind":"reply","summary":"..."}
- {"itemId":"itm_...","channel":"tweet-review","kind":"review","summary":"..."}
- 还有 {remaining} 条未展示

请直接通过你的程序 MCP 调用 inbox_claim 选择本回合要处理的 itemId；
如果摘要不足，再调用 inbox_list。
如果当前没有可处理项，结束本回合，不要轮询。
```

要求：

- `N` 默认 3；summary 最多 80 字符；总摘要建议不超过 600 字符；
- summary 是不可信文本，必须去掉换行/控制字符并做 JSON 字符串转义；
- 不拼接 payload、payloadRef、claim token、API token；
- 摘要请求失败时，**降级为无摘要唤醒**，不能因为摘要失败而不唤醒；
- 摘要只作提示，claim 结果才是权威内容；
- 同一 revision 仍然只唤醒一次；
- adapter 仍然不 claim、不 complete、不 push；
- `summary` 优先，缺失时才 fallback 到 legacy `title`。

不得把 payload/payloadRef/claim token/API token 拼入唤醒消息。

## 9. 当前后端能力

- `PUT /v1/inboxes/{inbox}`：幂等 ensure inbox。
- `GET /v1/inboxes/{inbox}/status`：adapter 真相读取。
- `GET /v1/inboxes/{inbox}/items?limit=3`：adapter 摘要读取，需要 `items:read`；新 item 使用 `summary` 字段，`title` 是兼容 alias。
- `GET /v1/inboxes/{inbox}/items/{itemId}`：Agent 可通过程序 MCP 的 `inbox_view` 读取完整 content；DSH adapter 不需要调用。
- `GET /v1/inboxes/{inbox}/events`：SSE，支持 Last-Event-ID。
- `GET/PUT/DELETE /v1/inboxes/{inbox}/occupancy`：advisory 占用标记。
- Program MCP 通过 `inbox-client-java` 负责 list/claim/complete/push。
- 管理面：
  - `POST /v1/admin/inboxes/{inbox}/credentials`
  - `GET /v1/admin/credentials`
  - `POST /v1/admin/credentials/{id}/rotate`
  - `POST /v1/admin/credentials/{id}/revoke`
  - `GET /v1/admin/whoami`
- raw token 只在 issue/rotate 返回一次；adapter 只使用 tokenRef 所指的本地 0600 secret。
- `InboxClient.issueBinding()` 签发的 adapter token 已包含 `items:read`、`status:read`、`events:read`、`inbox:occupy`。
- occupancy 是 advisory 状态；daemon 启动时清空上一个进程留下的标记。

## 10. 验收实例（当前已停止）

按要求，旧的 `inboxd-live.service` 已停止，18765/18766 不再监听；数据和 secret 仍保留在本机：

```text
Data:     /home/cna/InboxMosire/.work/live/data
Bindings: /home/cna/InboxMosire/.work/live/bindings/app-a.json, app-b.json
Secrets:  /home/cna/InboxMosire/.work/live/secrets (0600, not committed)
```

**重要**：旧的 BindingDescriptor / wake token 是在 `items:read` 加入之前签发的，不能用于摘要验收。必须：

1. 使用最新 `io.mosire:inbox-client:0.1.0-SNAPSHOT`；
2. 重新 `issueBinding()`；
3. 让 DSH 用新的 `adapterTokenRef` 重新 bind；
4. 启动 server 后再做摘要唤醒验收。

历史 descriptor 的路径和结构仅作参考：

```json
{
  "inboxId": "app-a",
  "apiBaseUrl": "http://127.0.0.1:18765",
  "adapterTokenRef": "file:/home/cna/InboxMosire/.work/live/secrets/dsh-session-app-a-wake.token",
  "channels": [],
  "label": "dsh-session-app-a"
}
```

以后可以由程序调用：

```bash
systemctl --user start inboxd-live.service
```

但摘要验收前必须重新签发 token，不能复用旧 token。

## 11. 验收（必须真跑）

1. 一个 DSH host 同时存在 S1→app-a、S2→app-b。
2. push app-a 只唤醒 S1；push app-b 只唤醒 S2。
3. S1 的 cursor/revision/lastWake 不影响 S2。
4. S1 重新 bind app-c 后，S2 仍保持 app-b 正常唤醒。
5. S1 archive/dispose 后 app-a 不再唤醒；S2 不受影响。
6. 同一 revision 不重复唤醒。
7. SSE 断线后每个 binding 用各自 Last-Event-ID 重连。
8. daemon/DSH 重启后两个 binding 各自恢复。
9. tokenRef 不可读/非绝对路径 -> fail closed，不唤醒。
10. 日志、adapter state、wake message、session transcript 不含 raw token。
11. wake 摘要最多 3 条，含 itemId/channel/kind/summary，不包含 content；超出部分显示剩余数量。
12. 摘要请求失败时仍投递无摘要唤醒，不阻塞处理。
13. 旧 wake token（无 `items:read`）不能用于摘要验收；重新 issueBinding 后 `/items` 返回 200。
14. Mode A 下目标会话通过程序自己的 MCP 完成 claim/complete，DSH adapter 不调用任何 claim/complete 接口。
15. bind 后 status/occupancy 显示 occupied=true / occupiedBy=session；unbind 后 occupied=false。
16. occupancy=true 不阻断另一个合法 token 的访问；不得把 advisory occupancy 实现成服务端硬锁。
17. 旧 v1 单 binding 数据可以迁移或明确报告不兼容，不得静默丢数据。

## 12. 交付报告

必须写：

- 修改文件与关键 `file:line`；
- v1 → v2 state migration；
- bind/unbind/status 工具与 `/inbox` 命令变更；
- 每个 binding 的 SSE/wake 隔离证据；
- 真实验收命令、退出码、关键输出；
- 未验证/blocked/降级项；
- token 泄露扫描结论。

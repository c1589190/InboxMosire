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
- adapter wake token 需要 scope：`status:read`、`events:read`、`inbox:occupy`；
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
4. 若 idle 且 available > 0 且 revision > lastWokenRevision，则最多 followup 一次；
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

## 8. 固定唤醒模板

```text
[INBOX_HOST_EVENT]
inbox={inboxId} revision={revision} available={available} channels=[{changedChannels}]

这是通知板唤醒信号，不是通知内容。
请使用你的程序 MCP 的 inbox_list 查看条目，并用 inbox_claim 选择本回合要处理的项。
如果当前没有可处理项，结束本回合，不要轮询。
```

不得把 title/payload/payloadRef/claim token/API token 拼入唤醒消息。

## 9. 当前后端能力

- `PUT /v1/inboxes/{inbox}`：幂等 ensure inbox。
- `GET /v1/inboxes/{inbox}/status`：adapter 真相读取。
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
- occupancy 是 advisory 状态；daemon 启动时清空上一个进程留下的标记。

## 10. 当前 live 实例（2026-10-25）

后端已重启到新 jar，SQLite 从旧 schema 迁移成功，旧 main 凭据仍可用。

```text
API/SSE: http://127.0.0.1:18765
MCP:     http://127.0.0.1:18766/mcp
Data:    /home/cna/InboxMosire/.work/live/data
```

当前可用测试板：

- `main`：干净，旧兼容路径
- `app-a`：1 条 available 验证通知
- `app-b`：1 条 available 验证通知

程序侧已经用共享 `inbox-client` 生成好 BindingDescriptor：

```text
/home/cna/InboxMosire/.work/live/bindings/app-a.json
/home/cna/InboxMosire/.work/live/bindings/app-b.json
```

DSH 的实现应直接读取这两个 descriptor，把 S1 绑到 app-a、S2 绑到 app-b：

```json
{
  "inboxId": "app-a",
  "apiBaseUrl": "http://127.0.0.1:18765",
  "adapterTokenRef": "file:/home/cna/InboxMosire/.work/live/secrets/dsh-session-app-a-wake.token",
  "channels": [],
  "label": "dsh-session-app-a"
}
```

```json
{
  "inboxId": "app-b",
  "apiBaseUrl": "http://127.0.0.1:18765",
  "adapterTokenRef": "file:/home/cna/InboxMosire/.work/live/secrets/dsh-session-app-b-wake.token",
  "channels": [],
  "label": "dsh-session-app-b"
}
```

注意事项：

- tokenRef 文件权限为 `600`；不要把内容打印出来；
- DSH adapter 用这些 token 调 `status` + `events` + `occupancy`；
- 目标 session 的 `inbox_list/claim/complete` 走程序自己的 MCP，不走中心 MCP；
- app-a/app-b 各有一条 available 通知，可用于两个 session 的独立 wake 验收；
- 当前 occupancy 均未占用；DSH bind 成功后应 PUT occupancy，unbind 后 DELETE；
- occupancy 是 advisory 提示，不会阻止其他合法 token 访问。

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
11. Mode A 下目标会话通过程序自己的 MCP 完成 claim/complete，DSH adapter 不调用任何 claim/complete 接口。
12. bind 后 status/occupancy 显示 occupied=true / occupiedBy=session；unbind 后 occupied=false。
13. occupancy=true 不阻断另一个合法 token 的访问；不得把 advisory occupancy 实现成服务端硬锁。
14. 旧 v1 单 binding 数据可以迁移或明确报告不兼容，不得静默丢数据。

## 12. 交付报告

必须写：

- 修改文件与关键 `file:line`；
- v1 → v2 state migration；
- bind/unbind/status 工具与 `/inbox` 命令变更；
- 每个 binding 的 SSE/wake 隔离证据；
- 真实验收命令、退出码、关键输出；
- 未验证/blocked/降级项；
- token 泄露扫描结论。

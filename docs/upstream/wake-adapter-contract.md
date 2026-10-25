# Wake Adapter 契约 v1

> 适用对象：`InboxMosire-DSH`，以及未来 Claude/Codex/其他 Agent 的 host adapter。
> 核心后端不实现本契约中的宿主调用；adapter 是唯一知道 session 和 `followup` 的组件。

## 1. 职责边界

Adapter **只能**：

1. 订阅 InboxMOSIRE SSE；
2. 查询 `status`；
3. 维护自己的 cursor 与 session 绑定；
4. 在宿主合适的时机调用宿主唤醒能力；
5. 在 session 归档/删除时解绑。

Adapter **不能**：

- 调用 MCP claim/complete；
- push 通知（除非单独持有 producer token，且不推荐与 adapter token 复用）；
- 在唤醒消息里放通知正文；
- 把 API token 放进 URL/日志/唤醒消息；
- 把通知投到未绑定或已归档 session。

## 2. 建议配置

```text
INBOX_API_URL=http://127.0.0.1:18765
INBOX_INBOX_ID=main
INBOX_API_TOKEN=<scoped adapter token>
INBOX_CHANNELS=ticket,tweet-review
INBOX_WAKE_DEBOUNCE_MS=300
INBOX_HEARTBEAT_TIMEOUT_SECONDS=45
```

token 必须是 `status:read` + `events:read`，按需要限制 inbox/channel。

## 3. 持久状态

Adapter 应在自己的状态文件中保存：

```json
{
  "inboxId": "main",
  "sessionRef": "<host session id>",
  "channels": ["ticket", "tweet-review"],
  "lastEventId": 128,
  "lastObservedRevision": 42,
  "lastWokenRevision": 40,
  "enabled": true
}
```

- `sessionRef` 只能由宿主当前 session 上下文提供，不能由生产者传入。
- `lastEventId` 用于 SSE 重连。
- `lastWokenRevision` 用于同一 revision 不重复唤醒。
- session archived 时 `enabled=false`，并停止 followup；恢复后重新拉 status。

## 4. 启动/恢复流程

```text
1. 加载持久状态
2. 校验当前 session 是否存在、是否 archived
3. GET /status  ← 先补一次当前真相
4. 如果 idle 且 available > 0，按去重规则决定是否唤醒
5. GET /events 并带上 Last-Event-ID
6. 进入事件/生命周期循环
```

不带 cursor 重新连接 SSE 时，服务端从当前 tip 开始；因此 **先拉 status 是必须的**。

## 5. 事件处理状态机

```text
idle
  ├─ status.available > 0 且 revision > lastWokenRevision
  │      -> followup 一次，记录 lastWokenRevision
  └─ available = 0 -> 保持 idle

active turn
  ├─ 收到事件 -> 只标 dirty
  └─ turn end -> 再拉 status
        ├─ available > 0 -> 最多补一次 followup
        └─ available = 0 -> idle
```

必须满足：

- 当前 turn active 时不打中断醒；
- 同一 revision 不重复唤醒；
- 一个 adapter 同时最多一个未完成 wake；
- burst 事件做 debounce（建议 200–500 ms）；
- 连续唤醒但 revision/available 无进展时指数退避，并把异常记到 adapter 状态；
- SSE 断线按指数退避重连，带最后成功的 event id；
- 401/403 停止唤醒并记录配置错误；
- heartbeat 超时后重连，不要自己轮询通知板。

## 6. 固定唤醒模板

推荐：

```text
[INBOX_HOST_EVENT]
inbox=main revision=42 available=3 channels=[ticket,tweet-review]

这是通知板唤醒信号，不是通知内容。
请调用 inbox_list 查看你要处理的条目，并用 inbox_claim 选择本回合要处理的项。
如果当前没有可处理项，结束本回合，不要轮询。
```

要求：

- 只含 inbox id、revision、available 数、受控 channel id；
- 不含 title/payload/payloadRef/claim token/API token；
- 不把生产者提供的任意字符串直接拼接进模板。

## 7. Session 生命周期

| 状态 | 行为 |
|---|---|
| idle | 有新 available 时允许 followup |
| active turn | 只标 dirty；turn end 后最多一次 followup |
| cold / resumed | 先恢复绑定、拉 status，再允许一次补唤醒 |
| archived / deleted | 立即禁用，不投递 |
| 未知 session | fail closed，不猜、不 fallback |
| 服务重启 | 读本地 cursor 与 lastWokenRevision，重新接 SSE 前先拉 status |

## 8. DSH 接管点

`InboxMosire-DSH` 只需要：

1. 从 DSH 当前 session 上下文取权威 session id；
2. 订阅 DSH session 的 turn start/end、resume、archive 生命周期；
3. 调用 DSH 的 followup/agent 唤醒入口；
4. 在 DSH 持久状态中保存本契约的 cursor 与 binding。

如果 DSH 没有 turn-end/idle 生命周期钩子，不能保证持续循环；此时必须显式标为降级，不能伪装成可靠唤醒。

## 9. 手工验收

1. Agent idle，推送 3 条，adapter 只 followup 一次。
2. Agent active，推送 5 条，adapter 当前 turn 不打断，turn end 后最多补一次。
3. Agent claim 2 条、完成 1 条后结束 turn，剩余通知会继续处理。
4. 空板 10 分钟推一条，只有一次新唤醒。
5. 服务重启后 adapter 不重复唤醒已完成 revision。
6. SSE 断开，重连后用 Last-Event-ID 补到事件；status 仍为最终真相。
7. 唤醒消息和 adapter 日志里搜不到正文/API token。

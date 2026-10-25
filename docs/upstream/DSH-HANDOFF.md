# 给 InboxMosire-DSH 的交接单

> 目标：DSH 插件只做 **SSE 客户端 + session 生命周期 + followup**。
> 通知板业务、claim、complete 全部在 InboxMosire 后端；不要在 DSH 项目里复制。

## 1. 后端入口

启动后端：

```bash
java -jar inbox-server-0.1.0-SNAPSHOT-all.jar init --data-dir ./data
java -jar inbox-server-0.1.0-SNAPSHOT-all.jar token issue \
  --data-dir ./data \
  --label dsh-adapter \
  --scope status:read \
  --scope events:read \
  --inbox main \
  --channel ticket \
  --channel tweet-review
java -jar inbox-server-0.1.0-SNAPSHOT-all.jar serve --data-dir ./data
```

默认：

```text
API/SSE: http://127.0.0.1:18765
MCP:     http://127.0.0.1:18766/mcp
```

Agent 的 MCP 配置由 DSH 侧注册，不是 adapter 去调用。DSH plugin **不持有 MCP client**，除非你在做工具排障。

## 2. DSH 需要实现的最小状态

```ts
type DshInboxBinding = {
  inboxId: string;
  sessionRef: string;          // 来自 DSH 当前 session 上下文，不能让 producer 传
  channels: string[];
  lastEventId: number | null;
  lastObservedRevision: number;
  lastWokenRevision: number;
  enabled: boolean;
};
```

持久化到 DSH 自己的 store；重启后恢复。

## 3. 启动 / 恢复流程

```text
load binding
-> if inactive/archived: stop
-> GET status
-> if session idle && status.available > 0 && status.revision > lastWokenRevision:
     followup fixed template once
     lastWokenRevision = status.revision
-> connect SSE:
     GET /v1/inboxes/{inbox}/events
     Authorization: Bearer <adapter token>
     Accept: text/event-stream
     Last-Event-ID: <lastEventId if present>
-> on event:
     update changedChannels / dirty
     debounce 300ms
     re-check status before calling followup
```

没有 `Last-Event-ID` 时 SSE 从当前 tip 开始，所以启动时必须先拉 status。

## 4. 唤醒状态机

```text
if session archived/unknown:
    no-op

else if turn active:
    dirty = true
    // 不打断当前回合

else if status.available > 0 && status.revision > lastWokenRevision:
    call host.followup(fixedTemplate)
    lastWokenRevision = status.revision
```

`turn end` 时：

```text
status = GET status
if status.available > 0 && status.revision > lastWokenRevision:
    call host.followup(fixedTemplate)   // 最多一次
    lastWokenRevision = status.revision
else:
    idle
```

## 5. 固定唤醒模板

```text
[INBOX_HOST_EVENT]
inbox={inboxId} revision={revision} available={available} channels=[{changedChannels}]

这是通知板唤醒信号，不是通知内容。
请调用 inbox_list 查看你要处理的条目，并用 inbox_claim 选择本回合要处理的项。
如果当前没有可处理项，结束本回合，不要轮询。
```

必须：

- 只拼接受控的 inbox id、数字和 channel id；
- 不拼 item title / payload / message body；
- 不把 API token 放进 followup 元数据、日志或 URL；
- 一个 revision 最多唤醒一次。

## 6. SSE 响应示例

```text
id: 129
event: inbox.changed
data: {"specversion":"1.0","id":"evt_...","source":"urn:inboxmosire:inbox:main","type":"io.mosire.inbox.changed.v1","subject":"main","time":"...","datacontenttype":"application/json","data":{"inbox":"main","revision":8,"available":1,"claimed":0,"channels":{"ticket":{"available":1,"claimed":0}},"changedChannels":["ticket"]}}

```

注意：

- `id:` 是 cursor；重连时放到 `Last-Event-ID` 请求头。
- 不要把 `data` 当正文；它只是 invalidation。
- 每次 followup 前都应实际 GET `status`，不要让 SSE 事件内容替代 status。

## 7. DSH 生命周期的硬要求

DSH 插件必须有：

1. 获取当前 session 的权威 id；
2. 观察 turn start / turn end；
3. 观察 session resume / archive / delete；
4. 有一个可被本插件调用的 followup/唤醒 API；
5. followup 尽可能带幂等键（建议以 `inbox + revision + sessionRef` 生成）。

若第 2 点不存在，只能做“新事件时唤醒一次”的降级版，不能声称实现了可靠的持续处理循环。

## 8. 必测

- 空板时 push 一条 → 只唤醒一次；
- active turn 中 push 多条 → 当前 turn 不中断；
- turn end 仍有 available → 只补一次；
- 已处理完成 revision 不重复唤醒；
- SSE 断线后带 Last-Event-ID 重连；
- archived session 不投递；
- 唤醒消息和日志搜索不到 payload、title、API token；
- DSH 重启后 cursor / lastWokenRevision 恢复。

## 9. 参考文件

- `docs/upstream/http-sse-contract.md`
- `docs/upstream/wake-adapter-contract.md`
- `docs/api/openapi.yaml`
- `docs/upstream/mcp-tools.md`

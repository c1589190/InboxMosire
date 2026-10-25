# 给 InboxMosire-DSH 的对接提示词

把下面整段提示词交给负责 `/home/cna/InboxMosire-DSH` 的工程 Agent。

---

你是负责 `InboxMosire-DSH` 的工程 Agent。目标是把 DSH 的宿主唤醒能力接到已经实现并跑起来的 **InboxMosire Java 后端** 上。

## 0. 先认清边界

- 后端仓库：`/home/cna/InboxMosire`
- DSH 适配器仓库/目录：`/home/cna/InboxMosire-DSH`
- 后端是 Agent 无关的 daemon，已经实现并冻结 v1 HTTP/SSE/MCP 契约。
- **DSH 适配器只负责**：
  1. 订阅 InboxMosire SSE；
  2. 读取 `status`；
  3. 绑定当前 DSH session；
  4. 在 idle / turn-end 时调用 DSH 宿主自己的 followup/唤醒入口；
  5. 持久化 cursor、revision、binding；
  6. session resume/archive/delete 生命周期处理。
- **DSH 适配器不负责**：通知存储、claim、complete、MCP 工具实现、业务动作。
- 不要在 DSH 项目里复制后端逻辑。
- 不要修改后端 API；若发现契约缺口，先报告，不要静默绕过。

## 1. 必读契约

按顺序阅读：

1. `/home/cna/InboxMosire/docs/upstream/DSH-HANDOFF.md`
2. `/home/cna/InboxMosire/docs/upstream/wake-adapter-contract.md`
3. `/home/cna/InboxMosire/docs/upstream/http-sse-contract.md`
4. `/home/cna/InboxMosire/docs/api/openapi.yaml`
5. `/home/cna/InboxMosire/docs/upstream/mcp-tools.md`（仅排障/理解 Agent 侧，不要从 adapter 调 MCP）

## 2. 当前可验证后端实例

本机已启动后端，默认地址：

```text
API/SSE: http://127.0.0.1:18765
MCP:     http://127.0.0.1:18766/mcp
Data:    /home/cna/InboxMosire/.work/live/data
```

开发期 adapter token 放在本机 secret 文件：

```text
/home/cna/InboxMosire/.work/live/secrets/dsh-adapter.token
```

要求：

- 读取 token 后只放在 `Authorization: Bearer ...` 请求头；
- 不要打印、不要写日志、不要放 URL、不要提交；
- 当前 token scope 是 `status:read` + `events:read`，inbox=`main`；
- 不要用它与 producer token 混用。

先做只读健康检查：

```bash
curl -fsS http://127.0.0.1:18765/healthz
curl -fsS http://127.0.0.1:18765/readyz
curl -fsS http://127.0.0.1:18765/v1/inboxes/main/status \
  -H "Authorization: Bearer $(tr -d '\n' < /home/cna/InboxMosire/.work/live/secrets/dsh-adapter.token)"
```

当前 live 实例状态：

- `main` 是干净板，适合最终接 DSH；
- `smoke` 板当前有 1 条验证通知，适合先做闭环测试；
- `smoke` 的 adapter token：`.work/live/secrets/dsh-smoke-adapter.token`；
- `smoke` 的 producer token：`.work/live/secrets/smoke-producer.token`；
- 完整 runtime env：`.work/live/runtime.env`；
- 现场验证证据：`.work/live/logs/verification.txt`；
- 不要为了测试把 main 弄脏；先用 smoke，确认状态机后再切 main。

## 3. 必须先探测 DSH 宿主能力

动手写代码前先找到并报告 DSH 的真实 API/文件行：

1. 如何获取当前 session 的权威 id；
2. 是否有 turn start / turn end / idle 生命周期事件；
3. 是否有 session resume / archive / delete 事件；
4. 是否有 `agent.followup()` 或等价唤醒入口；
5. followup 是否支持幂等键；
6. adapter 状态应持久化到哪个 DSH store。

如果缺少 turn-end 或等价能力，**不要伪装成可靠唤醒**：实现可工作的降级版并在报告中明确写 `DEGRADED`，说明哪条验收无法满足。

## 4. 实现要求

### 4.1 配置

至少支持：

```text
INBOX_API_URL=http://127.0.0.1:18765
INBOX_INBOX_ID=main
INBOX_API_TOKEN=<from local secret>
INBOX_CHANNELS=ticket,tweet-review
INBOX_WAKE_DEBOUNCE_MS=300
```

也可以从 DSH 本机配置读取，但 token 不得入库到仓库或日志。

### 4.2 持久状态

至少持久化：

```json
{
  "inboxId": "main",
  "sessionRef": "<DSH authoritative session id>",
  "channels": ["ticket"],
  "lastEventId": 128,
  "lastObservedRevision": 42,
  "lastWokenRevision": 40,
  "enabled": true
}
```

### 4.3 启动/恢复

1. 加载状态并校验当前 session；
2. 先 GET `status`；
3. 如果 session idle、`available > 0`、`revision > lastWokenRevision`，最多 followup 一次；
4. 再连 SSE，带 `Last-Event-ID`；
5. 没有 cursor 时从当前 tip 开始，所以不能跳过第 2 步。

### 4.4 唤醒状态机

```text
archived / unknown session -> 不投递
active turn               -> 只标 dirty，不打断
idle && available > 0     -> 最多一次 followup
turn end                  -> 重拉 status，必要时最多一次 followup
available = 0             -> idle
```

要求：

- 同一 revision 不重复唤醒；
- 一个 adapter 同时最多一个未完成 wake；
- burst 事件 200–500ms debounce；
- SSE 断线指数退避重连；
- 断线时保留最后成功 event id；
- 401/403 停止自动唤醒并报配置错误；
- 连续无进展唤醒要退避，不能 token storm；
- followup 幂等键建议：`inbox:<id>:revision:<n>:session:<ref>`。

### 4.5 固定唤醒消息

只能使用固定模板 + 受控字段：

```text
[INBOX_HOST_EVENT]
inbox={inboxId} revision={revision} available={available} channels=[{changedChannels}]

这是通知板唤醒信号，不是通知内容。
请调用 inbox_list 查看你要处理的条目，并用 inbox_claim 选择本回合要处理的项。
如果当前没有可处理项，结束本回合，不要轮询。
```

不得拼接 title/payload/payloadRef/claim token/API token。

### 4.6 隐私与安全

- SSE data 只当 invalidation，不当正文；
- 唤醒消息、日志、持久状态中不得出现 API token；
- 不把 token 放 URL；
- 不因订阅了事件就获得 DSH 的额外工具权限；
- 归档 session 立即停止 followup；
- 不向未知/错误 session 投递。

## 5. 验收测试

必须真实运行并附命令、退出码、关键输出、日志位置：

1. `main` 空板时 adapter 不唤醒。
2. 向 `smoke` inbox push 一条，idle session 只唤醒一次。
3. active turn 期间 push 多条，当前 turn 不中断，turn end 后最多补一次。
4. Agent claim 2 条、complete 1 条后结束 turn，剩余通知能被继续处理。
5. 同一 revision 不重复唤醒。
6. SSE 断线重连，用 `Last-Event-ID` 补发。
7. archived session push 后不唤醒。
8. DSH 重启后 cursor / lastWokenRevision / binding 恢复。
9. 搜索 adapter 日志与唤醒消息，确认没有 payload/title/token。
10. 完整演示目标循环：有空闲通知 → 唤醒 → Agent 选多条 claim → 处理 → complete → 若还有则继续/再次唤醒，若空则静默等待。

## 6. 交付报告格式

报告必须包含：

- 修改文件清单 + 关键 `file:line`；
- DSH 宿主能力探测结论；
- 持久状态位置与迁移方式；
- SSE/生命周期实现说明；
- 真实验收命令与结果；
- 未验证/降级/blocker；
- token 泄露检查结论；
- 不要用“应该可以”替代真实运行证据。

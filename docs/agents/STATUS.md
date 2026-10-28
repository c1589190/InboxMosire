# STATUS

- 日期：2026-10-27
- 分支：`main`
- 版本：`0.1.0-SNAPSHOT`
- 阶段：通用 item、409 冲突错误、DSH view-ack 与 session 级休息已实现；Bot 侧后续映射/参数待做
- 最近 Inbox 功能提交：`6013cde feat(embedded): 参数化 host/port/path 与 claim sweeper`
- 最近 Inbox 契约提交：`2c4b1c2 docs(errors): 发布 item 冲突 code/details 契约`
- 最近 DSH 提交：`113a838 feat(rest): 以标准 inbox_rest 工具设置休息`

## 2026-10-28 依赖刷新

- AgentLib 固定 `0.2.4`（源码最新；公开 Pages 仍为 0.2.3，发布落差记录在 iiBot 计划）。
- Jackson 统一 `2.22.4`，根 POM 导入 `jackson-bom`，修复 `jackson-dataformat-yaml` 曾解析为 2.18.4 的版本混杂。
- SLF4J `2.0.20`；JUnit `6.1.3`；SpotBugs annotations `4.10.5`；
  Maven compiler/resources/surefire/jar/enforcer 更新到当前稳定版。
- 验证：`./mvnw -B -ntp clean verify` PASS，**37 tests / 0 failures / 0 errors / 0 skipped**；
  dependency tree 显示 Jackson 全部为 2.22.4（annotations 2.22）。
- 真实外部联调仍 `NOT_RUN`。

## 已完成

- Java 21 + Maven 多模块：
  - `inbox-core`
  - `inbox-sqlite`
  - `inbox-client`
  - `inbox-mcp-tools-client`
  - `inbox-server`
  - `inbox-embedded`
- 多 inbox：后端数据模型、MCP 参数、`PUT /v1/inboxes/{id}` 幂等 ensure。
- Token 生命周期：
  - `items:write/read/manage`
  - `status:read/events:read`
  - `inbox:ensure`
  - `credentials:issue:self`
  - admin scopes
  - issue/list/rotate/revoke/whoAmI
  - fingerprint、expiry、lastUsedAt、rotatedFrom、createdBy
  - raw token 只在 issue/rotate 返回一次
- 共享客户端 `inbox-client`：
  - `InboxClient`
  - `TokenStore`
  - `BindingDescriptor`
  - ensure/push/status/list/view/update/append/delete/claim/renew/complete/release
  - credential issue/list/rotate/revoke/whoAmI
  - SSE stream
- Session 级休息：
  - 新增标准 MCP 工具 `inbox_rest({restSeconds})`；
  - `inbox_complete` / `inbox_delete` 也接受可选 `restSeconds`；
  - DSH 观察成功完成工具结果，设置当前 binding 的 `restUntil`；
  - 休息期间不拉 status/summary、不 followup；
  - 到期后最多补一次最新 revision 的唤醒；
  - `restSeconds=0` 清除；最大 86400 秒；
  - 休息只作用于当前 binding，不静音整个 Inbox；
  - 中心/程序 MCP 都暴露 `inbox_rest`；Bot 的 `InboxClientMcpTools` 更新并重建后自动获得，无需改 Bot ack 工具。
  - 推荐流程：`inbox_rest({restSeconds:60})`，不依赖 bot_thread_ack 的 schema。
- 通用 Item 错误与生命周期：
  - `ITEM_STATUS_CONFLICT`：claimed/done 不可修改/删除，带 `itemStatus` 与 `allowedActions`；
  - `ITEM_REVISION_CONFLICT`：expectedRevision 不匹配，带 expected/actual；
  - `InboxException`、HTTP `ApiError`、`InboxClientException` 均保留安全 details；
  - `view` 已归档 item 仍是 404，修改/删除 done 返回状态冲突。
- 通用 Item：
  - `summary`：短摘要，列表和 wake 摘要使用；
  - `content`：不透明完整内容，程序/用户自定义文本、Markdown 或 JSON 字符串；
  - `contentRef` 可选；
  - `title/payload/payloadRef` 保留兼容；
  - `inbox_list` 只返回 summary；
  - `inbox_view` 返回 summary + content，不 claim；
  - `inbox_claim` 返回 summary + content；
  - update/append/delete 推进 revision 并写 event；
  - 仅 `available` item 可修改；claimed/done 返回 409 `ITEM_STATUS_CONFLICT`，stale revision 返回 `ITEM_REVISION_CONFLICT`。
- 中心后端 MCP 12 工具：
  - status/rest/list/view/update/append/delete
  - claim/renew/complete/release
  - push
- 程序自有 MCP 16 工具：
  - 中心 12 个
  - occupancy 三件套
  - `inbox_credential_info`（仅 metadata）
- HTTP：
  - `GET /v1/inboxes/{inbox}/items/{itemId}`
  - `PATCH /v1/inboxes/{inbox}/items/{itemId}`
  - `POST /v1/inboxes/{inbox}/items/{itemId}/append`
  - `DELETE /v1/inboxes/{inbox}/items/{itemId}`
  - 新增 `items:manage` 保护的修改面；
  - `items:read` 可 view，不返回 content 到列表/SSE/wake。
- Embedded 参数化初始化：
  - `EmbeddedInboxOptions` 新增 `apiHost/apiPort/mcpHost/mcpPort/mcpPath/claimSweepInterval`；
  - `start(options)` 全量透传 `ServerConfig`，`0` 端口仍是随机；
  - `apiUrl()/mcpUrl()/client()` 使用实际 host、bound port、路径；
  - 固定端口绑定失败 fail-closed；
  - `InboxDaemon.start(config, claimSweepInterval)` 可配置 sweeper，旧 `start(config)` 保持 5s；
  - `serverConfig()/claimSweepInterval()/apiPort()/mcpPort()` 诊断 accessor；
  - loopback-only、端口、path、正 Duration 校验已补齐。
- Advisory inbox occupancy：
  - `GET/PUT/DELETE /v1/inboxes/{inbox}/occupancy`
  - scope `inbox:occupy`
  - status 返回 `occupied/occupiedBy/occupiedAt`
  - 占用不阻塞其他合法 token；daemon 启动清空旧占用
- 文档：
  - `docs/upstream/http-sse-contract.md`
  - `docs/upstream/mcp-tools.md`
  - `docs/upstream/DSH-SUMMARY-CONTENT-PROMPT.md`
  - `docs/upstream/program-integration.md`
  - `docs/api/openapi.yaml`
  - `设计-服务端架构.md` v1.3 增补

## 最近验证

```bash
./mvnw -B -ntp clean install
```

结果：

- `BUILD SUCCESS`
- Tests run: **37**，Failures 0, Errors 0, Skipped 0
- Checkstyle：7 个模块全部 0 violations
- `inbox-embedded` 8 个测试，覆盖随机/固定端口、自定义 path、fail-closed、accessor、数据保留
- `inbox-client`、`inbox-mcp-tools-client`、`inbox-embedded` 已安装到 `~/.m2`
- 测试覆盖：
  - Client view/update/append/delete；
  - claimed item 修改返回 409；
  - 中心 MCP view/update/append/delete；
  - 程序自有 MCP 15 工具注册；
  - legacy `title/payload` HTTP 输入兼容；
  - 原有 claim/complete/SSE/token/occupancy 用例保持通过。
- `scripts/gate.sh full` 通过；本次验证使用临时 SQLite + 真实进程内 HTTP/MCP daemon；另有一个外部手动 daemon 正在 18765/18766 运行。
- 本次未重跑真实 DSH host/ACP 场景；下一步是配置真实 DSH connector（scope/URL/Bearer）并跑一次 view-ack wake 验收。

## DSH 对接状态

- `InboxMosire-DSH` 已完成 summary/content 与 WebUI inbox panel，基线 `8015748`；
- 后端 `/items` 同时返回 `summary` 和 legacy `title`；
- DSH 已完成本轮修复：
  - `wakeMode=view-ack` 不出现 `inbox_claim`，claim 模式保持兼容；
  - `hasBusinessTools` 识别 `inbox_view`（含 MCP 前缀）；
  - `inbox_adapter_status` 主动刷新 `programMcp`，不必等下一次 wake；
  - 新增 `docs/PROGRAM-MCP-ATTACH.md`，说明 connector scope/URL/Bearer；
  - DSH 代码在 `6b71f6b`，已提交并推送。
- Bot 侧待做：
  - 更新 `inbox-mcp-tools-client` 依赖并重建，获得 16 工具，其中包含 `inbox_rest`；
  - `InboxClientDigestPort.mapFailure` 区分 `ITEM_STATUS_CONFLICT` / `ITEM_REVISION_CONFLICT`；
  - `BotThreadAckTool` 不要所有异常都压成 `INBOX_CONFLICT`；
  - ack 后保留 cursor-only state，避免 digest 从 seq 0 重放；
  - 状态冲突时不要 `states.clear()` 后立即 push 新 item。
- 程序 MCP 新增 view/update/append/delete；目标 Agent 通过程序自有 MCP 使用，DSH adapter 不暴露这些工具。

## live 实例

`inboxd-live.service` 仍是 inactive；但当前有一个手动启动的 daemon 正在监听 18765/18766：

```text
PID 33757
data: /home/cna/InboxMosire/.work/live/data
```

不要把它和 systemd service 状态混为一谈。

数据、bindings、token secret 保留在本机 `.work/live`，未删除：

```text
Data:     .work/live/data
Bindings: .work/live/bindings/app-a.json, app-b.json
Secrets:  .work/live/secrets (0600, not committed)
```

## 已知限制

- update/append/delete 只允许 `available`；
- claimed item 不做原地 contentRevision/stale-complete；
- 删除是归档，不是物理删除；
- 事件 outbox 暂无 retention/清理策略；
- 中心 MCP 仍是回环无鉴权；Mode A 通过程序自有 MCP 做业务租户边界；
- raw token 服务端重看未实现，需要时 rotate；
- 无 Web UI、无远程公网支持；
- 不做跨宿主自动工具调用或 content 链接解析。

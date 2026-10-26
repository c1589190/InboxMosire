# STATUS

- 日期：2026-10-26
- 分支：`main`
- 版本：`0.1.0-SNAPSHOT`
- 阶段：通用 summary/content item 已实现；中心 MCP 11 工具、程序自有 MCP 15 工具；等待 DSH 切换 summary 字段
- 最近功能提交：`b7136ad feat(item): 统一 summary/content 并新增 view/update/append/delete`
- 最近契约提交：`1be515f docs(api): 定义通用 summary/content item 与 view/update/append/delete`

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
- 通用 Item：
  - `summary`：短摘要，列表和 wake 摘要使用；
  - `content`：不透明完整内容，程序/用户自定义文本、Markdown 或 JSON 字符串；
  - `contentRef` 可选；
  - `title/payload/payloadRef` 保留兼容；
  - `inbox_list` 只返回 summary；
  - `inbox_view` 返回 summary + content，不 claim；
  - `inbox_claim` 返回 summary + content；
  - update/append/delete 推进 revision 并写 event；
  - 仅 `available` item 可修改，claimed 返回 409 `CONFLICT`。
- 中心后端 MCP 11 工具：
  - status/list/view/update/append/delete
  - claim/renew/complete/release
  - push
- 程序自有 MCP 15 工具：
  - 中心 11 个
  - occupancy 三件套
  - `inbox_credential_info`（仅 metadata）
- HTTP：
  - `GET /v1/inboxes/{inbox}/items/{itemId}`
  - `PATCH /v1/inboxes/{inbox}/items/{itemId}`
  - `POST /v1/inboxes/{inbox}/items/{itemId}/append`
  - `DELETE /v1/inboxes/{inbox}/items/{itemId}`
  - 新增 `items:manage` 保护的修改面；
  - `items:read` 可 view，不返回 content 到列表/SSE/wake。
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
- Tests run: **31**，Failures 0, Errors 0, Skipped 0
- Checkstyle：7 个模块全部 0 violations
- `inbox-client`、`inbox-mcp-tools-client`、`inbox-embedded` 已安装到 `~/.m2`
- 测试覆盖：
  - Client view/update/append/delete；
  - claimed item 修改返回 409；
  - 中心 MCP view/update/append/delete；
  - 程序自有 MCP 15 工具注册；
  - legacy `title/payload` HTTP 输入兼容；
  - 原有 claim/complete/SSE/token/occupancy 用例保持通过。
- 本次未启动 live daemon；验证使用临时 SQLite + 真实进程内 HTTP/MCP daemon。
- 本次未重跑真实 DSH host/ACP 场景；DSH 下一阶段按新提示词做 summary 字段切换。

## DSH 对接状态

- `InboxMosire-DSH` 已完成 wake summary，commit `8c2bdaa feat(summary): bounded item summaries in wake messages`；
- 当前 DSH summary 仍读取 `title`；
- 后端 `/items` 同时返回 `summary` 和 legacy `title`，所以 DSH 现在不会断；
- 下一阶段提示词：
  `docs/upstream/DSH-SUMMARY-CONTENT-PROMPT.md`
- DSH 必改：
  - `normalizeItems` 优先 `summary`，缺失 fallback `title`；
  - wake 摘要 JSON 使用 `summary`；
  - 不请求/不保存/不输出 content；
  - 继续不 claim/complete/push；
  - 不做跨工具自动跳转。
- 程序 MCP 新增 view/update/append/delete；目标 Agent 通过程序自有 MCP 使用，DSH adapter 不暴露这些工具。

## live 实例（已停止）

按要求 `inboxd-live.service` 仍停止，18765/18766 不监听。

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

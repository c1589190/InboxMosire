# STATUS

- 日期：2026-10-25
- 分支：`main`
- 版本：`0.1.0-SNAPSHOT`
- 阶段：后端多程序接入 v2、embedded server、DSH v2 多 binding 均已完成
- 最近代码提交：`56df47a feat(embedded): 提供一行启动独立 InboxMosire server 的共享库`

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
  - ensure/push/status/list/claim/renew/complete/release
  - credential issue/list/rotate/revoke/whoAmI
  - SSE stream
- 程序自有 MCP `inbox-mcp-tools-client`：
  - 11 个工具，包含 occupancy 三件套和 `inbox_credential_info`（仅 metadata）
  - 不返回 raw token
- Advisory inbox occupancy：
  - `GET/PUT/DELETE /v1/inboxes/{inbox}/occupancy`
  - scope `inbox:occupy`
  - status 返回 `occupied/occupiedBy/occupiedAt`
  - 占用不阻塞其他合法 token；daemon 启动清空旧占用
- 服务端 HTTP action endpoints：
  - `GET /items`
  - `POST /claims`
  - `/claims/renew|complete|release`
- DSH v2 对接提示词：
  - `docs/upstream/DSH-MULTI-BINDING-PROMPT.md`

## 最近验证

```bash
./mvnw -B -ntp clean verify
```

结果：

- `BUILD SUCCESS`
- Tests run: **30**，Failures 0, Errors 0, Skipped 0
- Checkstyle：所有模块 0 violations
- Maven install 成功，共享库已安装到 `~/.m2`
- 真实 live 验证：
  - 旧 SQLite schema 自动迁移，旧 token 仍可认证；
  - `inbox-client` 真程序创建 `app-a`/`app-b`；
  - 生成 program MCP token、adapter wake token、`BindingDescriptor`；
  - app-a/app-b 各有 1 条 available 验证通知；
  - wake token 访问 items 返回 403；
  - SSE 无 payload 秘密；
  - 日志/evidence/binding/raw token 泄漏扫描为 0；
- 共享库已安装到本地 Maven 仓库：
  - `io.mosire:inbox-client:0.1.0-SNAPSHOT`
  - `io.mosire:inbox-mcp-tools-client:0.1.0-SNAPSHOT`
  - `io.mosire:inbox-embedded:0.1.0-SNAPSHOT`
- `EmbeddedInboxServer.start("app-a")` 一行启动：
  - 独立临时 SQLite；
  - 随机回环 API/MCP 端口；
  - 自动创建 inbox；
  - 返回 apiUrl/mcpUrl/provisioner token/client；
  - close 停止 server 并清理临时目录。
- occupancy live 验证：
  - program token 占用 app-a 后 status 显示 occupied；
  - 另一个合法 status token 仍返回 200（advisory，不阻断）；
  - wake token 可读取并清除占用；
  - 清理后 app-a/app-b 仍各有 1 条 available item。

## live 实例

```text
API/SSE: http://127.0.0.1:18765
MCP:     http://127.0.0.1:18766/mcp
Data:    .work/live/data
Bindings: .work/live/bindings/app-a.json, app-b.json
Secrets: .work/live/secrets (0600, not committed)
```

## DSH 对接状态

用户确认隔壁 v2 验收成功。`InboxMosire-DSH` 已到 commit
`0391ea5 docs(acceptance): record the post-restart v2 web-profile loop`，远程 `origin/main` 同步。

已验收：

- 单 host 多 session binding；
- S1→app-a、S2→app-b；
- 每 binding 独立 SSE/cursor/revision/wake；
- Web profile 重启迁移 v1 state 到 v2；
- Mode A 下目标会话使用程序自有 MCP，不直连中心 MCP；
- occupancy bind/unbind 清理。

其他项目接入提示词：
`docs/upstream/OTHER-PROJECT-INTEGRATION-PROMPT.md`。

## 已知限制

- 事件 outbox 暂无 retention/清理策略。
- 中心 MCP 仍是回环无鉴权；Mode A 通过程序自有 MCP 做业务租户边界，部署时必须避免把中心 MCP 暴露给目标会话。
- raw token 服务端重看未实现；Program/Adapter 在本机 secret store 保存，服务端只提供 metadata/fingerprint。需要时用 rotate。
- 无 Web UI、无远程公网支持。

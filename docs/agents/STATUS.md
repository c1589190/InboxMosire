# STATUS

- 日期：2026-10-25
- 分支：`main`
- 版本：`0.1.0-SNAPSHOT`
- 阶段：后端多程序接入 v2 完成；等待 `InboxMosire-DSH` 多 binding 升级
- 最近代码提交：`bc6b239 feat: 增加多 inbox provision、credential 管理与共享客户端/MCP 工具面`

## 已完成

- Java 21 + Maven 多模块：
  - `inbox-core`
  - `inbox-sqlite`
  - `inbox-client`
  - `inbox-mcp-tools-client`
  - `inbox-server`
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
  - 8 个工具，包含 `inbox_credential_info`（仅 metadata）
  - 不返回 raw token
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
- Tests run: **27**，Failures 0, Errors 0, Skipped 0
- Checkstyle：所有模块 0 violations
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

## live 实例

```text
API/SSE: http://127.0.0.1:18765
MCP:     http://127.0.0.1:18766/mcp
Data:    .work/live/data
Bindings: .work/live/bindings/app-a.json, app-b.json
Secrets: .work/live/secrets (0600, not committed)
```

## 等待 DSH

`InboxMosire-DSH` 当前仍是 1 inbox / 1 binding 版本。下一步按
`docs/upstream/DSH-MULTI-BINDING-PROMPT.md` 升级到 v2：

- 单 host 多 session binding；
- S1→app-a、S2→app-b；
- 每 binding 独立 SSE/cursor/revision/wake；
- 读取 `adapterTokenRef`，不接触 raw token 日志；
- Mode A 下目标会话使用程序自有 MCP，不直连中心 MCP。

## 已知限制

- 事件 outbox 暂无 retention/清理策略。
- 中心 MCP 仍是回环无鉴权；Mode A 通过程序自有 MCP 做业务租户边界，部署时必须避免把中心 MCP 暴露给目标会话。
- raw token 服务端重看未实现；Program/Adapter 在本机 secret store 保存，服务端只提供 metadata/fingerprint。需要时用 rotate。
- 无 Web UI、无远程公网支持。

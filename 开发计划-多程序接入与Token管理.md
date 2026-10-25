# 开发计划：多程序接入、动态 Inbox 绑定与 Token 分发

> 日期：2026-10-25  
> 状态：Plan v2，待最后确认后实施  
> 关联：`设计-服务端架构.md`、`docs/api/openapi.yaml`、`docs/upstream/DSH-HANDOFF.md`
>
> 用户追加确认（原话）：
> - “1 一个dsh按需在不同会话绑定不同inbox”
> - “2 应该是，但是inbox-client-java要有能力拿到，我预想的工作流是拿到了这个jar插件的程序自己拿到inbox id和token，然后用自己的mcp工具返回给目标会话，目标会话自己连inbox”

## 1. 最终目标形态

```text
程序 A（嵌入 inbox-client-java）
  │ 1. ensure inbox app-a
  │ 2. 获取 app-a 的 inbox id + 受限 token
  │ 3. 通过自己的 MCP 工具把 ConnectionDescriptor 返回给目标会话
  ▼
目标会话
  │ 4. 用自己的 MCP/HTTP 连接能力连接 inbox app-a
  ▼
InboxMosire daemon（多 inbox、单状态源）
  ▲
  │ SSE + status
  │
DSH host（单个 DSH 进程内按 session 管理多个 binding）
  └── session S1 ↔ inbox app-a
      session S2 ↔ inbox app-b
      session S3 ↔ inbox app-c
```

关键变化：

1. **DSH 不再按“一个 adapter row 一个 inbox”作为主路径**；DSH adapter 要在宿主内维护 `sessionRef -> binding` 映射，按需为不同会话绑定不同 inbox。
2. **`inbox-client-java` 是真正的共享客户端库**；调用方程序能拿到 inbox id 和受限 token，并决定是否通过自己的 MCP 工具交给目标会话。
3. 核心后端仍是唯一状态源；程序不直接碰 SQLite、不启动第二套 inbox server。

---

## 2. 两个连接模式

用户描述的工作流要支持，但必须区分“目标会话怎么连”：

### 模式 A：目标会话使用程序自己的 MCP（推荐默认）

```text
程序 MCP 工具 -> inbox-client-java -> InboxMosire HTTP
```

- token 留在程序进程内；
- 目标会话只拿到 `inboxId` 或一个短连接句柄；
- 不需要改 InboxMosire MCP 鉴权；
- 适合程序自己的业务工具和 inbox 查看工具在同一个 MCP server 里。

### 模式 B：目标会话直接连 InboxMosire MCP（用户描述的目标）

```text
程序 MCP 工具 -> 返回 ConnectionDescriptor
目标会话 -> 自带 Bearer token 连接 InboxMosire MCP/HTTP
```

需要新增后端能力：

- MCP HTTP endpoint 支持 `Authorization: Bearer`；
- token 绑定 inbox（目标会话不能借 token 访问其它 inbox）；
- 动态 MCP 连接必须由目标宿主支持；如果 DSH 不能从工具结果动态注册 MCP server，则模式 B 在 DSH 侧必须由 adapter/用户配置承担，不能假装已实现。

**计划同时覆盖 A 和 B；B 是显式目标，但会先做宿主能力探测。**

---

## 3. `inbox-client-java` 必须提供的能力

### 3.1 客户端 API

```java
InboxClient client = InboxClient.builder()
    .baseUrl(URI.create("http://127.0.0.1:18765"))
    .credentialSource(CredentialSource.file(Path.of("program.token")))
    .build();

// 创建/确保 inbox
InboxDescriptor inbox = client.admin().ensureInbox("app-a", "程序 A");

// 为目标会话签发受限连接凭据
IssuedCredential issued = client.admin().issueCredential(
    IssueCredentialRequest.builder("app-a")
        .label("session-S1")
        .scope("status:read")
        .scope("events:read")
        .scope("mcp:connect")
        .expiresIn(Duration.ofHours(8))
        .build());

// 目标会话使用
ConnectionDescriptor connection = issued.connectionDescriptor(); // 含 inboxId, mcpUrl, token
```

### 3.2 ConnectionDescriptor

```json
{
  "inboxId": "app-a",
  "apiBaseUrl": "http://127.0.0.1:18765",
  "mcpUrl": "http://127.0.0.1:18766/mcp",
  "token": "inbox_...",
  "scopes": ["status:read", "events:read", "mcp:connect"],
  "expiresAt": "2026-10-25T08:00:00Z"
}
```

- 允许调用方程序读取 token 原文并放入自己的 MCP 工具返回；
- token 只能是“连接凭据”，不能是 admin/provisioner credential；
- 建议短 TTL，绑定单 inbox，可轮换/撤销；
- 程序应把 token 写入自己的本机 secret store，不写日志。

### 3.3 本地 TokenStore

```java
TokenStore store = TokenStore.file(Path.of("/home/.../app-a-session.token"));
store.writeAtomic(token, PosixFilePermissions.fromString("rw-------"));
String token = store.read();
TokenMetadata info = client.credentials().whoAmI();
```

- 程序能“拿到/查看”自己的 token；
- 服务端只返回一次原文；
- 服务端 metadata 列表不含原文；
- MCP 工具可以由调用方程序选择是否返回原文，但默认应为短 TTL 连接 token，并明确它已进入模型/session 上下文。

---

## 4. DSH 多会话多 inbox 绑定

### 4.1 目标

一个 DSH host 进程支持：

```text
bindings = {
  "session-1": { inboxId: "app-a", cursor, revision, tokenRef },
  "session-2": { inboxId: "app-b", cursor, revision, tokenRef },
  "session-3": { inboxId: "app-c", cursor, revision, tokenRef }
}
```

同一个 DSH 会话默认只绑定一个 inbox；重新 bind 即替换该会话的 binding。不同会话可绑定不同 inbox。

### 4.2 DSH adapter 需要改造

在 `InboxMosire-DSH`（不是 Java 后端）中：

- 状态文件从单一 binding 迁移为 `bindings` map；
- 每个 binding 拥有独立 SSE、cursor、revision watermark、token reference；
- `bind(sessionRef, inboxId, credentialRef/token)`：
  - 停止该 session 旧 binding；
  - 写入新 binding；
  - 先读 status，再连 SSE；
- `unbind(sessionRef)` 只影响目标 session；
- `agent/disposed`、`session-stop`、workspace archive 清理该 session binding；
- 每个 session 的 wake 去重独立；
- 不允许把同一 SSE 事件投递给错误 session；
- state file 必须按 binding 保持原子更新。

### 4.3 工具面变更

现有：

```text
inbox_adapter_bind({ sessionId? })
```

需要扩展为：

```text
inbox_adapter_bind({
  sessionId?,        // 默认调用者
  inboxId,           // 必填
  token?,            // 连接 token；也可用 tokenRef
  channels?,
  tokenRef?          // 推荐：程序把 token 写本地 secret 后只传引用
})
```

安全要求：

- token 不写普通日志；
- token 不进入 adapter state 原文；state 存 tokenRef 或加密后的 secret ref；
- token 原文如果按用户工作流通过 MCP 工具传入，会进入该会话 transcript，这是已知代价；默认推荐 `tokenRef`，或把 token 作为短 TTL 连接凭据。
- 绑定成功后只唤醒该 session；失败不 fallback 到其它 session。

---

## 5. 后端 API 增量

### 5.1 Inbox ensure

```http
PUT /v1/inboxes/{inbox}
Authorization: Bearer <provisioner-or-admin-token>
Content-Type: application/json

{"ownerLabel":"app-a"}
```

- 幂等创建；
- 普通精确绑定 token 只能 ensure 自己的 inbox；
- 通配/任意创建仅 admin 凭据允许。

### 5.2 Credential 管理

```http
POST /v1/admin/inboxes/{inbox}/credentials
GET  /v1/admin/credentials?inbox=...
POST /v1/admin/credentials/{id}/rotate
POST /v1/admin/credentials/{id}/revoke
GET  /v1/admin/whoami
```

签发返回原文一次：

```json
{
  "credential": {
    "id": "cred_...",
    "label": "session-S1",
    "scopes": ["status:read", "events:read", "mcp:connect"],
    "inbox": "app-a",
    "channels": [],
    "fingerprint": "sha256:ab12...",
    "expiresAt": "...",
    "createdAt": "..."
  },
  "token": "inbox_..."
}
```

### 5.3 MCP 鉴权（模式 B 必需）

目标会话要直接连 InboxMosire MCP，需要新增：

- MCP HTTP transport 读取 `Authorization: Bearer`；
- token -> credential -> inbox 绑定；
- 工具执行上下文只能访问该 inbox；
- 工具参数中的 `inbox` 若出现，必须等于 token 绑定 inbox，否则拒绝；
- `inbox_push` 也需要按 token scope 限制；
- MCP 面不暴露 token/admin 工具。

**技术门槛**：AgentLib 当前 `AgentToMcpServer.startHttp` 没有 HTTP header 鉴权注入。需要先评估两条路：

1. 给 AgentLib 增加 `McpHttpCallerResolver`（推荐，通用）；
2. 在 InboxMosire 侧做认证网关/每 inbox 子 MCP server（更重，尽量避免）。

这一项单独作为 Phase 3 门禁；不确认宿主/AgentLib 能承载前，不宣称模式 B 已可用。

---

## 6. 数据模型变更

`inbox`：

- `owner_label`
- `created_by`
- 可选 `archived_at`

`api_credential`：

- `label`
- `scopes`
- `inbox_id`
- `channels`
- `token_hash`
- `fingerprint`
- `expires_at`
- `last_used_at`
- `rotated_from`
- `revoked_at`
- `created_by`

要求：

- 只有 `token_hash`，默认不存 token 原文；
- `ConnectionDescriptor.token` 只在 issue/rotate 响应中出现一次；
- 程序本地 TokenStore 负责后续持久化；
- 日志/SSE/wake/MCP metadata 不显示 hash 之外更敏感内容（fingerprint 可显示）。

Migration version：`2`，旧 `main` / `smoke` / 现有 credential 自动兼容。

---

## 7. 分阶段实施

### Phase 0：契约与宿主能力探测

- 冻结 `ConnectionDescriptor`、`InboxDescriptor`、credential scopes、错误码。
- 探明 DSH 是否能从工具结果动态注册/连接 MCP；如果不能，模式 B 在 DSH 侧走 adapter 受控连接。
- 更新 OpenAPI 与 DSH 多 binding 设计。

验收：设计文档落盘，token 生命周期无歧义。

### Phase 1：服务端 provisioning / credential API

- `PUT /v1/inboxes/{id}`
- credential issue/list/rotate/revoke/whoami
- scope：`inbox:ensure`、`credentials:issue:self`、admin 级 credential scope
- migration v2、审计、token 过期

验收：

- 程序能创建自己的 inbox；
- token 原文只在 issue/rotate 出现一次；
- 精确 inbox token 不能创建/访问别的 inbox；
- 轮换后旧 token 401。

### Phase 2：`inbox-client-java`

- HTTP client
- admin/provisioner API
- producer API
- reader/SSE API
- `TokenStore`
- `ConnectionDescriptor`
- 测试

验收：

- 一个外部 Java 程序可以 ensure -> issue -> push -> status；
- client 能拿到 token 并写入 0600 secret；
- client 不依赖 `inbox-server`/SQLite/AgentLib 服务端。

### Phase 3：MCP 直连认证（模式 B 门禁）

- 评估/实现 AgentLib MCP bearer 解析或认证网关；
- token 绑定 inbox；
- 工具面 inbox 强制收窄；
- MCP 不返回 token 原文。

验收：

- 目标会话用 ConnectionDescriptor 直连 MCP；
- 只能看到/操作 token 绑定的 inbox；
- 伪造 inbox 参数被拒；
- 未带 token 401。

### Phase 4：DSH 多 binding

在 `InboxMosire-DSH`：

- 单进程多 session binding；
- 每 session 独立 SSE；
- bind/unbind 按 session；
- tokenRef/secret 管理；
- archive/resume 清理；
- 单 session 依然只绑一个 inbox，重新 bind 替换。

验收：

- S1→app-a、S2→app-b 同时运行；
- app-a 事件只唤醒 S1；
- app-b 事件只唤醒 S2；
- S1 归档后不唤醒；
- S1 重启/重连恢复自己的 cursor；
- 一个 inbox 的 revision 不串到另一个 session。

### Phase 5：程序自有 MCP 工具包（模式 A）

- `inbox-mcp-tools-client`：网络版 AgentTool：
  - `inbox_status/list/claim/complete/release/renew/push`
  - 可选 `inbox_credential_info`（仅 metadata）
- 注册进程序自己的 AgentLib MCP server。

验收：

- 程序自己 MCP 可展示/操作 inbox；
- 原文 token 不出现在 MCP tool result；
- claim/complete 经真实 HTTP 到 daemon。

### Phase 6：端到端与发布

- 两个程序、两个 inbox、两个 DSH session；
- 程序 A MCP 返回连接描述 → session S1 连接 app-a；
- 程序 B 返回连接描述 → session S2 连接 app-b；
- push/claim/complete/rotate/archive/restart 全流程；
- 隐私扫描、文档、OpenAPI、client release。

---

## 8. 硬验收

1. 一个 DSH host 同时绑定 S1→app-a、S2→app-b，互不串线。
2. `inbox-client-java` 能从服务端获取 inbox id 和一次性 token，并写入本地 secret。
3. 程序 MCP 工具返回的 ConnectionDescriptor 可供目标会话使用；原文 token 不写日志。
4. 目标会话只得到自己的 inbox 能力，不能通过参数越权访问别的 inbox。
5. token 轮换后旧 SSE/MCP connection 必须失效或明确重连失败。
6. session archive 后该 binding 停止，其他 session 不受影响。
7. 重启 daemon 后 inbox、token metadata、cursor、binding 恢复。
8. 所有日志/SSE/wake/transcript 扫描无 token 原文泄露。
9. 旧单 binding adapter 和旧 main/smoke 配置不破坏，可迁移。

---

## 9. 仍需确认的一点

“目标会话自己连 inbox”的技术落点需要锁定：

- **模式 A（程序自有 MCP proxy）**：最容易，不需要 MCP 鉴权改造；目标会话只调用程序 MCP 工具。
- **模式 B（目标会话直连 InboxMosire MCP）**：需要 MCP Bearer 鉴权 + DSH/宿主支持动态 MCP 连接；工作量和宿主能力风险都更高。

计划已同时写入两种模式；Phase 3 是模式 B 的前置门禁。没有该门禁通过，就不能对外宣称“目标会话拿到 token 后可直接连 InboxMosire MCP”。

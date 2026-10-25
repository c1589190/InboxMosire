# 开发计划：多程序接入、共享客户端库与 Token 管理

> 日期：2026-10-25  
> 状态：待用户确认后实施  
> 关联架构：`设计-服务端架构.md`、`docs/api/openapi.yaml`、`docs/upstream/DSH-HANDOFF.md`
>
> 用户原话：
> - “1 支持”
> - “4 B！共享出去的lib要支持获取、查看token，因为其他程序也会自己提供MCP，方便查看”
> - “出开发计划先”

## 1. 目标与主路径

主路径采用 **方案 B：一个程序一个 inbox，Agent/DSH 按需绑定**；同时保留方案 A 的共享 inbox + channel 能力。

```text
程序 A ── inbox-client-java ──► inbox app-a ── DSH adapter row A ── Agent session A
程序 B ── inbox-client-java ──► inbox app-b ── DSH adapter row B ── Agent session B
程序 C ── HTTP/OpenAPI ───────► inbox app-c ── DSH adapter row C ── Agent session C
                                      │
                                      └── 同一个 inboxd daemon + 同一个 SQLite/MCP
```

### 1.1 明确不做

- 不把整个 `inbox-server` / SQLite / MCP server 作为 lib 嵌入每一个程序。
- 不让程序直接打开 InboxMosire SQLite。
- 不让 DSH adapter 修改通知板状态；它仍然只做 SSE + session binding + followup。
- 不允许普通 producer token 创建任意 inbox。
- 不允许 MCP 工具返回 API token 原文。

## 2. 需要新增的层次

```text
                    ┌─────────────────────────────┐
                    │ inboxd（唯一状态源）         │
                    │ HTTP/SSE + MCP + SQLite     │
                    └─────────────┬───────────────┘
                                  │
             ┌────────────────────┼────────────────────┐
             ▼                    ▼                    ▼
     inbox-client-java      inbox-mcp-tools       DSH adapter
     （普通程序）            （其他程序的 MCP）    （读 SSE + wake）
```

### 2.1 `inbox-client-java`

新的共享 Java 客户端库，不含服务端实现。

建议 Maven 坐标：

```text
io.mosire:inbox-client:0.2.0
```

能力：

- `ensureInbox`
- `push`
- `status`
- `list`
- `claim`
- `complete`
- `release`
- `renew`
- `streamEvents`
- token 管理：`issue`、`list`、`rotate`、`revoke`、`whoAmI`
- 本地 `TokenStore`：环境变量、文件、显式字符串三种来源
- 统一错误码、重试、JSON DTO

不包含：

- SQLite
- JDK HttpServer
- InboxService
- AgentLib 服务端
- DSH 宿主代码

### 2.2 `inbox-mcp-tools-client`

可选 Maven 模块，供“其他程序自己提供 MCP”使用。

它把 `inbox-client-java` 包装成 AgentLib `AgentTool` 集合，可注册进程序自己的 `ToolRegistry` / `AgentToMcpServer`。

安全边界：

- 暴露 `inbox_status/list/claim/complete/release/renew/push`；
- 不暴露 admin/token 签发/raw token 工具；
- 可暴露 `inbox_credential_info`，但只返回 metadata（id/label/scopes/inbox/channels/fingerprint），不返回原文。

## 3. 多 Inbox 与绑定能力

### 3.1 后端

现状已经支持多 inbox：

- `inbox` 表按 id 区分；
- item/claim/event 全部带 `inbox_id`；
- MCP 工具接受可选 `inbox` 参数；
- live 实例已有 `main`、`smoke`。

本计划补齐**运行时创建 inbox**和**多 inbox 运维面**，但继续保持单 daemon、单 SQLite owner。

### 3.2 DSH adapter

当前 DSH adapter 语义：

```text
1 adapter instance = 1 inbox + 1 binding + 1 stateFile
```

因此方案 B 的落地方式：

- 每个 inbox 一个 adapter row；
- 每 row 配置不同 `inboxId`、`stateFile`、`tokenFile`；
- 同一个 DSH session 若同时消费多个 inbox，需要多个 adapter row，会产生多次 followup。

本计划不要求马上改 DSH adapter 成多 binding。  
先支持并文档化“一 inbox 一 adapter row”，完成多程序独立投递与独立 Agent 消费。

未来若要“一个 DSH session 同时绑定多个 inbox”，另开设计：多 binding、每 binding 独立 SSE、session 级 wake 合并。

### 3.3 两种可用拓扑

- **B（主路径）**：一程序一 inbox，一 adapter row 一 inbox 一 session。
- **A（仍支持）**：多个程序共享一个 inbox，用 channel 区分；只需要一个有界 adapter。

## 4. Token 生命周期与安全

### 4.1 原则

1. token 原文**只在签发/轮换时返回一次**。
2. 服务端只保存 token 的 SHA-256 hash 和 fingerprint。
3. MCP 永远不返回 token 原文。
4. 普通 producer/adapter token 不得签发 token 或创建任意 inbox。
5. 所有 token 管理操作写审计日志（不含 token 原文）。
6. 日志、SSE、wake 消息、MCP tool result 中不出现 token 原文。

### 4.2 “获取/查看 token”的落地

共享 lib 支持两种“查看”：

- **程序本地 TokenStore**：签发后由客户端写入本机 0600 secret 文件，程序可读取、使用、查看自己的 token；
- **服务端 metadata**：通过 `whoAmI` / credential list 查看 `id/label/scopes/inbox/channels/fingerprint/lastUsedAt`，但不能反查原文。

不默认实现“服务端重新显示 token 原文”。如果以后确实需要，必须单独设计：

- 使用本地 master key 加密 token 后落库；
- 显式 admin 解锁；
- 每次 reveal 写审计；
- 风险与轮换机制单独评审。

## 5. API 增量

### 5.1 Inbox ensure

```http
PUT /v1/inboxes/{inbox}
Authorization: Bearer <bootstrap-or-admin-token>
Content-Type: application/json

{"ownerLabel":"app-a"}
```

语义：

- 幂等；不存在则创建，存在则返回 200；
- 新创建返回 201；
- 需要 `inbox:ensure` 或 admin scope；
- 普通 producer token 只有在 **精确绑定** 到该 inbox 时才能 ensure 自己的 inbox；
- `*` inbox 的普通 token 不允许通过 ensure 创建任意 inbox。

### 5.2 Token 管理

```http
POST /v1/admin/inboxes/{inbox}/credentials
GET  /v1/admin/credentials?inbox=...
POST /v1/admin/credentials/{id}/rotate
POST /v1/admin/credentials/{id}/revoke
GET  /v1/admin/whoami
```

签发请求：

```json
{
  "label": "dsh-adapter-app-a",
  "scopes": ["status:read", "events:read"],
  "channels": []
}
```

响应只在这里返回原文一次：

```json
{
  "credential": {
    "id": "cred_...",
    "label": "dsh-adapter-app-a",
    "scopes": ["status:read", "events:read"],
    "inbox": "app-a",
    "channels": [],
    "fingerprint": "sha256:ab12…",
    "createdAt": "..."
  },
  "token": "inbox_..."
}
```

管理 scope：

- `admin:credentials` 才能签发任意 inbox 的 token；
- `credentials:issue:self` 只能为绑定 inbox 签发 scope 不超过自身的 token；
- `whoami` 允许任何合法凭据调用，返回自身 metadata。

### 5.3 Inbox 列表/状态

```http
GET /v1/admin/inboxes
GET /v1/inboxes/{inbox}/status
```

Admin inbox 列表返回 metadata，不返回 item 内容。

## 6. 数据模型变更

`api_credential` 增量字段：

- `fingerprint`：token hash 的短前缀，用于人眼识别；
- `last_used_at`：最近认证时间，按节流写；
- `rotated_from`：轮换来源；
- `created_by`：签发者 credential id 或 CLI；
- `revoked_at` 继续保留。

`inbox` 增量字段：

- `owner_label`
- `created_by`
- `updated_at` 已有
- 可选 `archived_at`（后续阶段）

新 migration version：`2`。

兼容性：

- 现有 token 继续有效；
- 旧 `main`/`smoke` 自动补齐 metadata；
- `inbox` 默认 main 不变；
- OpenAPI 只增不改。

## 7. 客户端库设计

### 7.1 基础 API

```java
try (InboxClient client = InboxClient.builder()
        .baseUrl(URI.create("http://127.0.0.1:18765"))
        .tokenSource(TokenSource.file(Path.of(".../app-a.token")))
        .build()) {

  client.inboxes().ensure("app-a", "程序 A");

  client.items().push(
      PushItem.builder("app-a")
          .channel("ticket")
          .kind("reply")
          .title("工单更新")
          .payload(Map.of("id", "t-42"))
          .dedupeKey("ticket:t-42")
          .build());

  InboxStatus status = client.inboxes().status("app-a");
}
```

### 7.2 TokenStore

```java
TokenStore store = TokenStore.file(Path.of(".../secrets/app-a.token"));
String token = store.read();
store.writeAtomically(token, 0600);
CredentialView info = client.tokens().whoAmI();
```

- `read()` / `writeAtomically()` / `delete()` 只在客户端本地。
- `TokenStore` 不把 token 打印到日志。
- CLI/程序可以通过显式本地命令查看自己的 token 文件；MCP 调用不允许读取该文件。

### 7.3 事件流

客户端提供：

```java
client.events().stream("app-a", cursor, event -> { ... });
```

底层仍是 SSE + `Last-Event-ID`，供程序自建 MCP/Agent 或自建观察面使用。

## 8. 其他程序自己提供 MCP

新增可选模块 `inbox-mcp-tools-client`，依赖：

```text
inbox-client-java
agentlib-mosire
```

程序可以：

```java
InboxMcpTools tools = InboxMcpTools.forClient(client);
ToolRegistry registry = new ToolRegistry();
registry.registerAll(tools.list());

AgentToMcpServer.startHttp(..., registry, ..., authorizer);
```

这样程序自己的 MCP 可以同时提供：

- 本程序的业务工具；
- inbox 查看/claim/complete 工具；
- 可选的 inbox credential metadata 查看工具。

但：

- token 管理仍是本地 admin API 操作；
- MCP 只提供 metadata，不提供原文 token；
- 若程序要打印 token，由本地运维 CLI 完成，不交给模型。

## 9. 阶段计划

### Phase 0：冻结契约

产出：

- 更新 `设计-服务端架构.md`（多 inbox + token lifecycle）；
- 更新 OpenAPI；
- 更新 `docs/upstream/mcp-tools.md`；
- 明确 `inbox-client` 与 `inbox-mcp-tools-client` 的模块边界。

验收：

- 设计评审通过；
- token 原文不可经 MCP/SSE/日志获取的红线写入文档。

### Phase 1：服务端 admin/ensure API

- `PUT /v1/inboxes/{id}`
- credential issue/list/rotate/revoke/whoAmI
- scope 校验、精确 inbox 限制、audit
- schema migration v2

验收：

- 幂等 ensure；
- 普通 token 不能越权创建别的 inbox；
- token 只返回一次；
- rotate 后旧 token 失效；
- 不破坏现有 push/status/SSE。

### Phase 2：`inbox-client-java`

- `InboxClient`
- `InboxAdminClient`
- `TokenStore`
- HTTP 错误映射、重试、幂等
- 单元/集成测试

验收：

- 程序用 lib ensure -> push -> status；
- token 保存到 0600 文件；
- lib 能读取自己的 token 并展示 metadata；
- 不依赖服务端模块。

### Phase 3：`inbox-mcp-tools-client`

- 网络版 AgentTool 集合
- 与 AgentLib MCP server 真连接测试
- 排除 token 管理工具与原文

验收：

- 程序自己的 MCP 可列出 inbox 工具；
- claim/complete 经网络调用真实 daemon；
- MCP result 不含 raw token。

### Phase 4：方案 B 端到端

- 程序 A 创 app-a、拿 producer token，push
- 为 app-a 签发 adapter token
- DSH 第二个 adapter row 绑定 app-a 与 session A
- 程序 B/app-b/session B 并行验证
- 两个 inbox 状态不串

验收：

- A 的消息只唤醒 session A；
- B 的消息只唤醒 session B；
- 同一 item 不会被两个 session 同时 claim；
- archived session 不唤醒；
- 日志/SSE/wake/privacy 扫描通过。

### Phase 5：文档与兼容

- 多 adapter row 操作手册；
- token 轮换手册；
- OpenAPI/生成客户端；
- 升级说明；
- 版本发布。

## 10. 硬验收清单

1. 程序 A 可以通过 client lib 幂等 ensure `app-a`。
2. 程序 A 不能访问/创建 `app-b`。
3. token 签发只显示一次；list/whoAmI 只有 metadata。
4. MCP 不返回 token 原文。
5. 程序 A push → app-a revision/status 正确。
6. DSH session A 只绑定 app-a，不被 app-b 唤醒。
7. 多个 inbox 的 event seq、revision、claim 完全隔离。
8. 轮换 adapter token 后，旧 token 401，新 token 生效。
9. 重启 daemon 后 inbox/credential/metadata 保持。
10. SSE Last-Event-ID、keepalive、断线重连保持。
11. 日志/SSE/wake 消息/会话 transcript 无 token 原文。
12. 其他程序自己 MCP 的 inbox 工具有真实 HTTP 调用证据。

## 11. 仍需用户确认的一个安全点

“查看 token”有两种实现：

- **推荐**：lib 在签发时把 token 原文写入本机 0600 secret store，之后程序可读取；服务端只能查看 metadata/fingerprint，不能重新显示原文；
- **高风险可选**：服务端加密保存 token 原文，admin 可显式 reveal，并写审计。

本计划默认采用第一种。若要求“换机器后仍能从服务端重新查看 token 原文”，需要追加第二阶段设计。

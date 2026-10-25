# 开发计划：多程序接入、动态 Inbox 绑定与 Token 分发

> 日期：2026-10-25
> 状态：Plan v3；InboxMosire 侧 Phase 1/2/3 已实现，Phase 4 等待 DSH。连接模式 A 已冻结。
> 关联：`设计-服务端架构.md`、`docs/api/openapi.yaml`、`docs/upstream/DSH-HANDOFF.md`
>
> 用户原话：
> - “1 一个dsh按需在不同会话绑定不同inbox”
> - “2 应该是，但是inbox-client-java要有能力拿到，我预想的工作流是拿到了这个jar插件的程序自己拿到inbox id和token，然后用自己的mcp工具返回给目标会话，目标会话自己连inbox”
> - 对“目标会话自己连 inbox”的最终选择：**A——目标会话使用程序自己的 MCP，程序内部用 client + token 代理访问 InboxMosire。**

## 1. 冻结结论

### 1.1 主架构

```text
程序 A
  ├─ inbox-client-java：ensure inbox / issue token / push / 操作 inbox
  ├─ 自己的 MCP server：暴露 inbox_* 工具，内部用 token 调 InboxMosire
  └─ 返回 BindingDescriptor 给目标会话（默认不含 raw token）

目标会话 S1
  ├─ 调程序 A 的 MCP：inbox_list / claim / complete / push
  └─ 调 DSH adapter bind：inboxId + tokenRef → 建立唤醒

DSH host
  ├─ S1 ↔ inbox app-a
  ├─ S2 ↔ inbox app-b
  └─ S3 ↔ inbox app-c

InboxMosire daemon
  └─ 唯一状态源 + MCP + HTTP/SSE + SQLite
```

### 1.2 模式 A 的边界

- 目标会话**不直接连 InboxMosire MCP**；
- 目标会话调用程序自己的 MCP；
- 程序持有业务/连接 token，用 `inbox-client-java` 代理；
- DSH adapter 只负责给绑定 session 发 wake，不 claim/complete；
- 后端 MCP 继续保持 v1 形态，不需要马上改 Bearer 鉴权；
- `inbox-client-java` 必须能让调用方程序拿到 inbox id 和 token，但 token 默认只留在程序进程/本机 secret store，不进入模型可见的 MCP result。

### 1.3 不做什么

- 不把整个 server/SQLite 嵌入每个程序；
- 不让目标会话直接拿 raw token 去连 InboxMosire MCP；
- 不让普通 producer token 创建任意 inbox；
- 不让 DSH adapter 操作通知内容；
- 不为模式 B 提前做 MCP 鉴权改造。

---

## 2. 程序侧工作流

### 2.1 启动/Provision

程序使用自己的 provisioner credential：

```java
InboxClient client = InboxClient.builder()
    .baseUrl(URI.create("http://127.0.0.1:18765"))
    .credentialSource(CredentialSource.file(Path.of("program-provisioner.token")))
    .build();

InboxDescriptor inbox = client.admin().ensureInbox("app-a", "程序 A");

IssuedCredential toolsToken = client.admin().issueCredential(
    IssueCredentialRequest.builder("app-a")
        .label("program-a-mcp")
        .scopes(Set.of("status:read", "events:read", "items:write"))
        .build());

IssuedCredential wakeToken = client.admin().issueCredential(
    IssueCredentialRequest.builder("app-a")
        .label("dsh-session-S1-wake")
        .scopes(Set.of("status:read", "events:read"))
        .build());
```

- `toolsToken` 留在程序 A 的 MCP server 内部；
- `wakeToken` 写入本机 secret 文件，返回 `tokenRef`；
- 两者都不进日志、不进程序对目标会话的 tool result。

### 2.2 返回给目标会话的 BindingDescriptor

```json
{
  "inboxId": "app-a",
  "apiBaseUrl": "http://127.0.0.1:18765",
  "adapterTokenRef": "file:/home/cna/.local/share/app-a/dsh-session-S1-wake.token",
  "channels": [],
  "label": "程序 A"
}
```

说明：

- `adapterTokenRef` 是本地 secret 的引用，不是 token 原文；
- 程序自己的 MCP 工具返回该 descriptor；
- 目标会话把 descriptor 交给 DSH adapter 的 bind 工具；
- DSH adapter 读取本地 secret 文件建立 SSE；
- token 文件必须 `0600`，且程序与 DSH 同用户可读。

### 2.3 目标会话使用程序 MCP

目标会话后续只调用：

```text
程序 A MCP:
  inbox_status
  inbox_list
  inbox_claim
  inbox_complete
  inbox_release
  inbox_renew
  inbox_push
```

每次调用 -> 程序 A 的 MCP handler -> `inbox-client-java` -> InboxMosire HTTP -> token 留在程序 A 内部。

### 2.4 唤醒链路

```text
inboxd SSE
   ↓
DSH adapter（读取 tokenRef）
   ↓ followup
目标会话 S1
   ↓
调用程序 A MCP 的 inbox_list / claim / complete
```

DSH adapter 不读 item 内容，不 claim，不 complete。

---

## 3. `inbox-client-java` 设计

### 3.1 模块

```text
inbox-client-java/
  InboxClient
  InboxAdminClient
  InboxProducerClient
  InboxReaderClient
  InboxMcpClientToolset
  TokenStore
  CredentialSource
```

依赖：JDK HttpClient、Jackson；不依赖 `inbox-server`、`inbox-sqlite`、AgentLib 服务端。

### 3.2 必须提供

- `ensureInbox`
- `push`
- `status`
- `list/claim/complete/release/renew`
- `streamEvents`（SSE + Last-Event-ID）
- `issueCredential`
- `listCredentials`（只返回 metadata）
- `rotateCredential`
- `revokeCredential`
- `whoAmI`
- `TokenStore.writeAtomic/read/delete`
- `BindingDescriptor` 生成

### 3.3 Token 获取/查看

- 程序可以通过 lib 的 issue/rotate API 拿到 token 原文；
- lib 支持将 token 写入本地 0600 secret file；
- 程序可以读取该文件用于自己的 MCP/proxy；
- 服务端 metadata list 返回 fingerprint/scopes/inbox/lastUsedAt，不返回原文；
- 默认不把 raw token 放进目标会话可见的 MCP result；
- 若某程序确实要调试 raw token，由它自己的本地 CLI/代码读取 secret file，不经过 MCP。

---

## 4. DSH 多 session 多 inbox 绑定

### 4.1 目标状态

```json
{
  "version": 2,
  "bindings": {
    "session-S1": {
      "inboxId": "app-a",
      "adapterTokenRef": "file:/.../S1.token",
      "channels": [],
      "lastEventId": 128,
      "lastWokenRevision": 40
    },
    "session-S2": {
      "inboxId": "app-b",
      "adapterTokenRef": "file:/.../S2.token",
      "channels": [],
      "lastEventId": 44,
      "lastWokenRevision": 10
    }
  }
}
```

一个 DSH host 管理多个 session binding；每个 session 当前只绑定一个 inbox，重新 bind 替换旧 binding。

### 4.2 DSH adapter 改造

- 状态从单 binding 迁移到 `bindings` map；
- 每 binding 独立 SSE、cursor、revision watermark、wake 状态；
- bind 参数扩展为：

```text
inbox_adapter_bind({
  sessionId?,
  inboxId,
  adapterTokenRef,
  channels?
})
```

- unbind 只移除目标 session；
- agent disposed / session-stop / archive 清理对应 binding；
- 同一事件只能唤醒其 binding 的 session；
- 每个 session 的 followup 去重独立；
- token 原文只从 `adapterTokenRef` 读取，不写入普通日志；
- 支持状态文件 v1 → v2 迁移。

### 4.3 当前 DSH 与目标差异

当前 `InboxMosire-DSH` 仍是 1 adapter instance = 1 inbox = 1 binding，bind 只接受 sessionId。要实现本计划，需要在 DSH 仓库做 v2 改造。

---

## 5. 后端 API 增量

### 5.1 Inbox ensure

```http
PUT /v1/inboxes/{inbox}
Authorization: Bearer <provisioner-or-admin-token>
Content-Type: application/json

{"ownerLabel":"程序 A"}
```

- 幂等；
- 201 创建 / 200 已存在；
- 精确绑定 token 只能 ensure 自己的 inbox；
- 任意创建只允许 admin/provisioner scope。

### 5.2 Credential 管理

```http
POST /v1/admin/inboxes/{inbox}/credentials
GET  /v1/admin/credentials?inbox=...
POST /v1/admin/credentials/{id}/rotate
POST /v1/admin/credentials/{id}/revoke
GET  /v1/admin/whoami
```

签发/轮换返回 raw token 一次；list/whoami 只返回 metadata。

### 5.3 作用域

- `inbox:ensure`
- `items:write`
- `status:read`
- `events:read`
- `credentials:issue:self`（只能在自身精确 inbox 内签发不高于自身权限的 credential）
- `admin:credentials`
- `admin:inboxes`

普通 producer token 不能签发 credential，不能创建其它 inbox。

### 5.4 MCP 面

保持现有无鉴权回环 MCP 和 Agent 工具面；不添加 raw token 管理工具。
程序自己的 MCP 使用 `inbox-client-java` proxy；因此本计划不需要改 AgentLib MCP HTTP 鉴权。

---

## 6. 数据模型

`inbox` 增量：

- `owner_label`
- `created_by`

`api_credential` 增量：

- `fingerprint`
- `expires_at`
- `last_used_at`
- `rotated_from`
- `created_by`

继续只保存 token hash；migration version 2，旧 main/smoke/credential 兼容。

---

## 7. 阶段计划

实施状态（2026-10-25）：

- Phase 1 服务端 provisioning/credential API：✅ 已实现。
- Phase 2 `inbox-client-java`：✅ 已实现。
- Phase 3 程序自有 MCP Toolset：✅ 已实现。
- Phase 4 DSH 多 binding：⏳ 交给 `InboxMosire-DSH`。
- Phase 5 端到端：⏳ 等 DSH v2。

补充（2026-10-25）：advisory occupancy 已先完成。它只是 `occupied/occupiedBy` 提示，不提供访问锁；访问控制仍由 token scope 决定。

### Phase 0：契约冻结

- 冻结 `BindingDescriptor`
- 冻结 credential scopes 和 tokenRef 格式
- 更新 OpenAPI
- 更新 DSH adapter v2 设计
- 确认 DSH 侧 bind 工具接受 descriptor

### Phase 1：服务端 provisioning/credential API

- `PUT /v1/inboxes/{id}`
- credential issue/list/rotate/revoke/whoami
- `credentials:issue:self`
- migration v2
- 审计与 token 过期

验收：

- 程序 A ensure app-a 成功；
- 程序 A 不能创建/访问 app-b；
- token 只返回一次；
- rotate 后旧 token 失效；
- 旧 API 不回归。

### Phase 2：inbox-client-java

- InboxClient/Admin/Producer/Reader
- TokenStore + CredentialSource
- BindingDescriptor
- HTTP/SSE 错误映射、幂等、测试

验收：

- 外部 Java 程序 ensure -> issue -> push -> status；
- 程序能读取 token 文件并注入自身 MCP；
- client 不依赖服务端模块。

### Phase 3：程序自有 MCP Toolset

- `inbox-mcp-tools-client`
- 包装 client 为 AgentLib `AgentTool`
- `inbox_status/list/claim/complete/release/renew/push`
- 可选 `inbox_credential_info`（metadata only）
- MCP 不返回 raw token

验收：

- 程序自己的 MCP 展示 inbox 工具；
- claim/complete 经真实 HTTP 到 daemon；
- tool result 不含 token 原文。

### Phase 4：DSH 多 binding v2

- state map migration
- per-session SSE
- bind/unbind descriptor
- tokenRef secret loading
- archive/resume cleanup
- wake 去重

验收：

- S1→app-a、S2→app-b 同时运行；
- 事件只唤醒正确 session；
- S1 归档不唤醒，S2 不受影响；
- S1/S2 cursor 独立；
- 重启恢复各自 binding。

### Phase 5：端到端

- 程序 A 创建 app-a、签发 token、启动自己的 MCP
- 程序 A MCP 返回 app-a BindingDescriptor
- DSH S1 bind app-a
- push -> DSH wake S1 -> S1 调程序 A MCP list/claim/complete
- 程序 B/S2 并行独立验证
- token rotate / inbox isolation / privacy scan

---

## 8. 硬验收

1. 一个 DSH host 可同时 bind S1→app-a、S2→app-b。
2. 程序 A 的 MCP 工具返回 BindingDescriptor，默认不含 raw token。
3. DSH adapter 通过 tokenRef 建立 SSE，不把 token 写日志。
4. 目标会话通过程序 MCP 完成 claim/complete。
5. 程序 A 不能访问 app-b。
6. token rotate 后旧 SSE 失效，新 token 可重连。
7. archive/resume 只影响本 session binding。
8. 重启后多个 session 的 cursor/revision 独立恢复。
9. 日志、SSE、wake、程序 MCP tool result 扫描无 raw token。
10. 既有一 inbox/channel、DSH 单 binding 路径不回归。

## 9. 剩余风险

- 如果 DSH 无法在同一插件实例内维护多 binding，则需要接受多个 adapter row 或先改 DSH；这是 Phase 4 的硬门槛。
- `tokenRef` 需要程序与 DSH host 共享同一本机用户/secret 目录；若跨用户或远程部署，需要改为短 TTL one-time ticket。
- 目标会话必须能看到并调用对应程序的 MCP server；如果 DSH 不能在会话级配置 MCP 工具可见性，多个程序的 `inbox_*` 工具可能重名/混用。此项在 Phase 3 做宿主能力验收。

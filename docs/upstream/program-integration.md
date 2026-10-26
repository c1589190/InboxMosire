# 其他程序接入规范（Mode A）

> 状态：0.1.0-SNAPSHOT 设计目标。
> 适用：把一个程序作为 inbox producer + 自有 MCP server + InboxMosire client 使用。
> 目标会话不直连 InboxMosire MCP。

## 1. 总链路

```text
程序（Java）
  ├─ InboxClient：ensure inbox / issue tokens / push / status
  ├─ 自有 MCP server：包装 InboxClientMcpTools
  └─ 给目标会话返回 BindingDescriptor（不含 raw token）

目标会话
  ├─ 调程序的 inbox_* MCP 工具（claim/complete/...）
  └─ 把 BindingDescriptor 交给 DSH adapter bind（只负责 wake）

InboxMosire daemon
  └─ HTTP/SSE + SQLite + 通用 MCP（Agent 工具面）
```

Mode A 的核心：**raw token 留在程序进程/本机 secret store，目标会话只使用程序自己的 MCP 工具**。
因此目标会话不应同时暴露中心 InboxMosire MCP；中心 MCP 是 lib/代理可用的通用面，不是 Mode A 的租户边界。

## 1.1 程序内嵌 server（一行启动）

如果其他程序不想预先部署 central daemon，可以直接依赖 `inbox-embedded`：

```java
try (EmbeddedInboxServer server = EmbeddedInboxServer.start("app-a")) {
  URI apiUrl = server.apiUrl();
  URI mcpUrl = server.mcpUrl();
  String token = server.provisionerToken();
  InboxClient client = server.client();
}
```

详细规则见 [`embedded-server.md`](embedded-server.md)。embedded server 默认只绑回环地址，每个实例有独立 SQLite 和随机端口。

## 2. 程序侧一次 Provision

### 2.1 依赖

```xml
<dependency>
  <groupId>io.mosire</groupId>
  <artifactId>inbox-client</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>

<dependency>
  <groupId>io.mosire</groupId>
  <artifactId>inbox-mcp-tools-client</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2.2 用 provisioner credential 建 inbox

Provisioner credential 由本机 CLI/管理员签发，建议 scopes：

```text
inbox:ensure
credentials:issue:self
items:write
items:read
items:manage
status:read
events:read
inbox:occupy
```

精确绑定到目标 inbox，例如 `app-a`。

```java
InboxClient provisioner = InboxClient.builder()
    .baseUrl(URI.create("http://127.0.0.1:18765"))
    .tokenSource(TokenSource.file(Path.of("/home/.../program-a-provisioner.token")))
    .build();

provisioner.ensureInbox("app-a");
```

### 2.3 签发程序自己的 MCP token

```java
InboxModels.IssuedCredentialView issued = provisioner.issueCredential(
    "app-a",
    new InboxModels.CredentialIssueRequest(
        "program-a-mcp",
        Set.of("items:write", "items:read", "items:manage", "status:read", "inbox:occupy"),
        Set.of(),
        86400L));

TokenStore.writeAtomic(
    Path.of("/home/.../program-a-mcp.token"),
    issued.token());

InboxClient programClient = InboxClient.forTokenFile(
    URI.create("http://127.0.0.1:18765"),
    Path.of("/home/.../program-a-mcp.token"));
```

token 原文只交给本地 secret store；不得写入日志、MCP result、会话 transcript。

### 2.4 启动程序自有 MCP

```java
InboxClientMcpTools tools = new InboxClientMcpTools(programClient, "app-a");
ToolRegistry registry = new ToolRegistry();
registry.registerAll(tools.all());

AgentToMcpServer server = AgentToMcpServer.startHttp(
    0, "/mcp", registry, "program-a-inbox", "0.1.0",
    ToolContext.of(...),
    ToolCallAuthorizer.of(new ToolExecutionGuard()));
```

程序 MCP 工具：

- `inbox_status`
- `inbox_list`
- `inbox_claim`
- `inbox_renew`
- `inbox_complete`
- `inbox_release`
- `inbox_push`
- `inbox_occupancy`
- `inbox_occupy`
- `inbox_vacate`
- `inbox_credential_info`（只返回 metadata/fingerprint，不返回 raw token）

程序还可以把自己的业务工具注册到同一个 ToolRegistry。

### 2.5 为目标会话签发 wake 绑定描述

```java
InboxModels.BindingDescriptor binding = provisioner.issueBinding(
    "app-a",
    "dsh-session-S1",
    Set.of(), // optional channel filter
    Path.of("/home/.../dsh-session-S1-wake.token"),
    Duration.ofHours(24));
```

返回：

```json
{
  "inboxId": "app-a",
  "apiBaseUrl": "http://127.0.0.1:18765",
  "adapterTokenRef": "file:/home/.../dsh-session-S1-wake.token",
  "channels": [],
  "label": "dsh-session-S1"
}
```

`adapterTokenRef` 指向本地 `0600` secret 文件，不包含 token 原文。程序通过自己的 MCP 工具把这个 descriptor 返回给目标会话；目标会话再把 descriptor 交给 DSH adapter 的 bind 工具。

`issueBinding` 签发的 wake token 包含 `status:read`、`events:read`、`items:read`、`inbox:occupy`，因此 DSH adapter 可以在 bind/unbind 时维护 advisory occupancy，并在唤醒前读取 bounded item 摘要。

程序也可以直接维护占用：

```java
programClient.occupy("app-a", "session-S1");
InboxModels.Occupancy occupancy = programClient.occupancy("app-a");
programClient.vacate("app-a");
```

occupancy 不是独占锁：它只是 `occupied/occupiedBy` 提示，其他合法 token 持有者仍可访问。

## 3. HTTP 端点

完整机器可读规范：[`docs/api/openapi.yaml`](../api/openapi.yaml)。

### 3.1 Provision

```http
PUT /v1/inboxes/{inbox}
```

需要 `inbox:ensure`（精确绑定 inbox）或 `admin:inboxes`。

### 3.2 操作面

```http
GET  /v1/inboxes/{inbox}/items
POST /v1/inboxes/{inbox}/items
POST /v1/inboxes/{inbox}/claims
POST /v1/inboxes/{inbox}/claims/renew
POST /v1/inboxes/{inbox}/claims/complete
POST /v1/inboxes/{inbox}/claims/release
```

scopes：

| 操作 | scope |
|---|---|
| push | `items:write` |
| list | `items:read` |
| claim/renew/complete/release | `items:manage` |
| status | `status:read` |
| SSE | `events:read` |

### 3.3 Credential 管理

```http
POST /v1/admin/inboxes/{inbox}/credentials
GET  /v1/admin/credentials?inbox=...
POST /v1/admin/credentials/{id}/rotate
POST /v1/admin/credentials/{id}/revoke
GET  /v1/admin/whoami
```

raw token 只在 issue/rotate 响应中出现一次；MCP 和 metadata 列表不返回原文。

## 4. Token 生命周期

- Program provisioner token：本机 CLI/管理员带入，长期放 secret store；
- Program MCP token：由 provisioner 签发，供程序自己的 MCP client 内部使用；
- Adapter wake token：由 `issueBinding` 签发给 DSH adapter，scope 含 `status:read`、`events:read`、`items:read`、`inbox:occupy`，存 `tokenRef` 指向的文件；
- 所有 token 可设置 TTL，可 rotate/revoke；
- rotate 旧 token 立即失效；
- 失效策略是 401，adapter/程序需重新加载 secret。

## 5. 多程序 / 多 inbox

每个程序推荐：

```text
inbox app-a
  ├─ program-a MCP token：items:*
  └─ adapter wake token：status:read, events:read, items:read, inbox:occupy
```

DSH host 的不同 session 可以分别绑定不同 inbox：

```text
session-S1 -> app-a
session-S2 -> app-b
```

程序 MCP 工具和 DSH binding 必须一一对应；目标会话调用正确的程序 MCP，DSH adapter 只负责唤醒，不 claim/complete。

## 6. 安全红线

1. raw token 不进 MCP result、SSE、wake、日志或仓库。
2. 目标会话不需要 raw token；只拿 BindingDescriptor/tokenRef。
3. 程序自己的 MCP 只能暴露 scoped inbox 工具；不得把 admin token 注入 MCP。
4. DSH adapter 只读取 tokenRef 对应文件，必须拒绝 non-absolute/不可读文件。
5. channel-scoped token 不能在 items list/claim 中越过 channel 范围。
6. 所有 API 默认只绑 `127.0.0.1`。

---

# v1.3：通用 Item 与程序自有 MCP 工具

每条通知现在只有：

```text
summary : 短摘要，列表和 wake 摘要使用
content : 不透明完整内容，程序/用户自定义
```

程序 MCP 工具增加：

```text
inbox_view
inbox_update
inbox_append
inbox_delete
```

完整工具（16 个）：

```text
inbox_status
inbox_rest
inbox_list
inbox_view
inbox_update
inbox_append
inbox_delete
inbox_claim
inbox_renew
inbox_complete
inbox_release
inbox_push
inbox_occupancy
inbox_occupy
inbox_vacate
inbox_credential_info
```

push 示例：

```java
client.push("app-a", InboxModels.PushItem.builder("ticket")
    .summary("工单更新")
    .content(Map.of("ticketId", "t-42", "body", "完整正文"))
    .dedupeKey("ticket:t-42")
    .priority(10)
    .build());
```

view/update/append/delete：

```java
InboxModels.ItemDetail detail = client.view("app-a", itemId);
client.update("app-a", itemId,
    new InboxModels.UpdateItem("新的摘要", "新的内容", null, null, 100, null));
client.append("app-a", itemId,
    new InboxModels.AppendItem("追加内容", null, null, null));
client.deleteItem("app-a", itemId, null);
```

边界：

- update/append/delete 只允许 available item；
- claimed item 返回 409，避免处理中被静默改写；
- 修改推进 revision 并触发事件；下一次 DSH 摘要会更新；
- `content` 不由 InboxMosire 解释，不会自动变成工具调用或链接。

兼容：

- `title/payload/payloadRef` 仍作为输入/输出别名；
- 新程序应使用 `summary/content/contentRef`。

## v1.5 冲突错误

`InboxClient` 的 `update`/`append`/`deleteItem` 在 HTTP 409 时会保留：

```java
catch (InboxClientException e) {
    e.code();     // ITEM_STATUS_CONFLICT / ITEM_REVISION_CONFLICT
    e.details();  // itemStatus, allowedActions, expectedRevision, actualRevision
}
```

- `ITEM_STATUS_CONFLICT`：item 已 claimed 或 done，不要重试同一删除；
- `ITEM_REVISION_CONFLICT`：重新 view 取得当前 revision 后再处理；
- 程序包装层不要把所有 409 都统一成同一个 `INBOX_CONFLICT`。

## v1.7 休息提示

优先调用项目自带的 `inbox_rest`（`InboxClientMcpTools` 已包含）：

```json
{"restSeconds":60}
```

这样无需修改程序的 ack 工具；DSH adapter 会观察成功结果并设置当前 binding 的
`restUntil`。如果程序想让完成工具直接带休息，也可以在自定义工具上增加可选
`restSeconds`。Inbox core 不保存或执行休息状态。

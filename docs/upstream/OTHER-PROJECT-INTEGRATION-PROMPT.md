# 给其他项目接入 InboxMosire 的提示词

把下面整段交给需要接入 InboxMosire、创建 inbox 的其他项目工程 Agent。

---

你是其他项目的工程 Agent。目标是把 InboxMosire 作为一个**独立通知板 server + 共享 Java 客户端**接入当前项目，并让当前项目可以创建/管理自己的 inbox、签发 token，再把可用连接交给目标 Agent。

## 1. 先选择运行方式

### 方式 A：当前项目内嵌启动（推荐）

依赖 `inbox-embedded`，由当前程序启动文件里的 server 实例：

```java
try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start("app-a")) {
  URI apiUrl = inbox.apiUrl();
  URI mcpUrl = inbox.mcpUrl();
  String inboxId = inbox.inboxId();
  String provisionerToken = inbox.provisionerToken();
  InboxClient client = inbox.client();
}
```

它会自动：

- 创建独立临时 SQLite；
- 随机回环 API/MCP 端口；
- 创建 `app-a` inbox；
- 返回精确绑定 app-a 的 provisioner token；
- 返回预配置 `InboxClient`；
- `close()` 时停止 server 并清理临时 dataDir。

### 方式 B：连接已有 daemon

已有 `inboxd` 时，只需：

```java
InboxClient client = InboxClient.builder()
    .baseUrl(URI.create("http://127.0.0.1:18765"))
    .tokenSource(TokenSource.file(Path.of("/path/to/provisioner.token")))
    .build();

client.ensureInbox("app-a");
```

首次 provisioner token 需要管理员/CLI 签发，且精确绑定目标 inbox。

## 2. Maven 依赖

```xml
<dependency>
  <groupId>io.mosire</groupId>
  <artifactId>inbox-embedded</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
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

如果目标项目机器没有这些 artifact，需要先发布到 Maven 仓库或从构建产物安装；不能把 raw token 写进仓库或配置示例。

## 3. 给目标 Agent 提供操作面

当前项目自己的 MCP server 应注册：

```java
ToolRegistry registry = new ToolRegistry();
registry.registerAll(
    new InboxClientMcpTools(inbox.client(), inbox.inboxId()).all());
// 再注册本项目的业务工具
```

模式 A 约束：

- 目标会话只调用**当前项目自己的 MCP**；
- 目标会话不要直连中心 InboxMosire MCP；
- 当前项目的 MCP token 留在当前程序内，不返回给模型；
- MCP 工具：`inbox_status/list/claim/renew/complete/release/push`、
  `inbox_occupancy/occupy/vacate`、`inbox_credential_info`。

程序 MCP token 建议 scopes：

```text
items:write
items:read
items:manage
status:read
inbox:occupy
```

## 4. 创建 token 和 DSH wake 绑定

```java
InboxModels.IssuedCredentialView mcpToken = inbox.client().issueCredential(
    inbox.inboxId(),
    new InboxModels.CredentialIssueRequest(
        "program-a-mcp",
        Set.of("items:write", "items:read", "items:manage", "status:read", "inbox:occupy"),
        Set.of(),
        86_400L));

TokenStore.writeAtomic(Path.of("/path/program-a-mcp.token"), mcpToken.token());

InboxModels.BindingDescriptor binding = inbox.client().issueBinding(
    inbox.inboxId(),
    "dsh-session-S1",
    Set.of(),
    Path.of("/path/dsh-session-S1-wake.token"),
    Duration.ofHours(24));
```

`binding` 不含 raw token，只含：

```json
{
  "inboxId": "app-a",
  "apiBaseUrl": "http://127.0.0.1:<port>",
  "adapterTokenRef": "file:/absolute/path/dsh-session-S1-wake.token",
  "channels": [],
  "label": "dsh-session-S1"
}
```

当前项目可以通过自己的 MCP 工具把这个 descriptor 返回给目标会话；目标会话交给 DSH adapter 的 bind 工具。DSH adapter 只读 `status/events/occupancy`，不负责 claim/complete。

## 5. Occupancy

DSH bind 后：

```http
PUT /v1/inboxes/{inbox}/occupancy
{"occupiedBy":"dsh-session-S1"}
```

unbind/session-stop 后：

```http
DELETE /v1/inboxes/{inbox}/occupancy
```

occupancy 只是 advisory 标记，**不是访问锁**；访问边界仍是 token scope。

## 6. 程序可管理的状态

当前程序可以经 `InboxClient` 管理：

- revision / available / claimed 状态；
- item push/list/claim/renew/complete/release；
- occupancy；
- SSE 事件；
- 子 credential issue/rotate/revoke 和自己 `whoAmI`；
- server 生命周期、端口、dataDir。

不要直接打开 SQLite 修改表；所有状态变更必须走 API/client。

## 7. 网络边界

`inbox-embedded` 默认只绑 `127.0.0.1`。

- 同机其他进程可以连接返回的 `apiUrl/mcpUrl`；
- 跨机器远程访问尚未开放；
- 如果目标 Agent 在另一台机器，必须另做远程绑定、TLS、鉴权和网络授权，不能把 loopback 地址当作远程可用地址。

## 8. 验收清单

1. `EmbeddedInboxServer.start("app-a")` 成功，`status("app-a")` 可读。
2. 自动创建指定 inbox，`provisionerToken()` 能 issue 子 token。
3. 当前项目 MCP `tools/list` 包含 inbox 工具。
4. 经当前项目 MCP 完成 push → list → claim → complete。
5. `issueBinding` 生成 descriptor，目标 DSH session 可 bind。
6. bind 后 occupancy=true/occupiedBy 正确；unbind 后清空。
7. 目标会话不直连中心 InboxMosire MCP。
8. raw token 不出现在 MCP result、日志、仓库、descriptor。
9. `close()` 后 HTTP/MCP 端口关闭；临时 dataDir 清理。
10. 显式 dataDir 时按配置保留数据并可重启恢复。

## 9. 必读文件

- `/home/cna/InboxMosire/docs/upstream/embedded-server.md`
- `/home/cna/InboxMosire/docs/upstream/program-integration.md`
- `/home/cna/InboxMosire/docs/upstream/http-sse-contract.md`
- `/home/cna/InboxMosire/docs/api/openapi.yaml`
- `/home/cna/InboxMosire/docs/upstream/mcp-tools.md`

如果上面任何依赖、API 或宿主能力当前不可用，报告 `BLOCKED`，不要用直接写 SQLite、明文 token 或绕过 API 的方式临时实现。

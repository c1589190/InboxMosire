# Embedded InboxMosire Server

其他 Java 程序只需要依赖 `inbox-embedded`，即可在自己的进程里启动一个独立 headless InboxMosire server。

> 产物已安装：`io.mosire:inbox-embedded:0.1.0-SNAPSHOT`。
> 一行 API：`EmbeddedInboxServer.start("app-a")`。

```xml
<dependency>
  <groupId>io.mosire</groupId>
  <artifactId>inbox-embedded</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 一行启动

```java
try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start("app-a")) {
  URI apiUrl = inbox.apiUrl();
  URI mcpUrl = inbox.mcpUrl();
  String provisionerToken = inbox.provisionerToken();
  InboxClient client = inbox.client();
  String inboxId = inbox.inboxId();
}
```

`start(String inboxId)` 会：

- 创建独立临时 data dir；
- 启动独立 SQLite + HTTP/SSE + MCP server；
- 使用随机回环端口；
- 自动创建指定 inbox；
- 返回只绑定该 inbox 的 provisioner token；
- 返回预配置 `InboxClient`；
- `close()` 停止 server 并删除临时 data dir。

如果只要默认 `main`：

```java
try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start()) {
  // ...
}
```

## 参数化启动

```java
EmbeddedInboxOptions options = EmbeddedInboxOptions.builder()
    .dataDir(Path.of("/var/lib/my-program/inbox"))
    .inboxId("app-a")
    .apiHost("127.0.0.1")
    .apiPort(0)
    .mcpHost("127.0.0.1")
    .mcpPort(0)
    .mcpPath("/mcp")
    .heartbeat(Duration.ofSeconds(15))
    .claimSweepInterval(Duration.ofSeconds(5))
    .deleteDataOnClose(false)
    .build();

try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start(options)) {
  // inbox.apiUrl() / inbox.mcpUrl() / inbox.provisionerToken()
}
```

显式给 `dataDir` 时默认不删除；只要临时目录时默认随 `close()` 删除。

### 字段和默认值

| Builder | 默认值 | 含义 |
|---|---|---|
| `dataDir(Path)` | 独立临时目录 | SQLite 数据目录 |
| `inboxId(String)` | `main` | 默认并自动创建的 inbox |
| `apiHost(String)` | `127.0.0.1` | API host，仅回环 |
| `apiPort(int)` | `0` | 0 表示随机可用端口 |
| `mcpHost(String)` | `127.0.0.1` | MCP host，仅回环 |
| `mcpPort(int)` | `0` | 0 表示随机可用端口 |
| `mcpPath(String)` | `/mcp` | 必须以 `/` 开头，不含 query/fragment |
| `heartbeat(Duration)` | 15 秒 | SSE heartbeat，必须为正 |
| `claimSweepInterval(Duration)` | 5 秒 | claim sweeper 初始延迟/周期，必须为正 |
| `deleteDataOnClose(boolean)` | 临时目录 true；显式目录 false | 关闭时删除数据目录 |

- 固定端口绑定失败会直接启动失败，绝不静默退回随机端口；
- 自定义 `mcpPath("/custom/mcp")` 时 `mcpUrl()` 返回实际 host、bound port 和 path；
- `apiUrl()` / `mcpUrl()` 使用实际配置 host 和实际 bound port；
- `serverConfig()`、`claimSweepInterval()`、`apiPort()`、`mcpPort()` 可用于诊断，不包含 token。

## 拿到什么

```text
apiUrl()            http://<apiHost>:<actual-api-port>
mcpUrl()            http://<mcpHost>:<actual-mcp-port><mcpPath>
inboxId()           app-a
provisionerToken()  一次返回的 exact-bound provisioner token
client()            已配置实际 apiUrl + token 的 InboxClient
dataDir()           当前实例数据目录
serverConfig()      有效请求配置（不含 token）
claimSweepInterval() 实际 sweeper 周期
apiPort()           实际 bound API port
mcpPort()           实际 bound MCP port
```

token scopes：

```text
inbox:ensure
inbox:occupy
credentials:issue:self
items:write
items:read
items:manage
status:read
events:read
```

`issueBinding()` 签发的 DSH wake token 还包含 `items:read`，允许 adapter 在唤醒前读 bounded item 摘要。

程序可以继续：

```java
InboxModels.IssuedCredentialView mcpToken = inbox.client().issueCredential(
    inbox.inboxId(),
    new InboxModels.CredentialIssueRequest(
        "program-mcp",
        Set.of("items:write", "items:read", "items:manage", "status:read", "inbox:occupy"),
        Set.of(),
        86400L));
```

## 当前网络边界

`inbox-embedded` 的 `apiHost/mcpHost` 仍只允许回环地址：

- 同机其他进程可以连接返回的 `apiUrl()` / `mcpUrl()`；
- `0.0.0.0`、公网地址等非回环 host 会在 builder 阶段被拒绝；
- 跨机器远程连接仍需要远程绑定、TLS 和网络访问控制，当前未开放。

## 生命周期

- 一个 embedded server 对应一个独立 SQLite 和一个独立端口组；
- 不与其他 embedded 实例共享状态；
- 调用方必须 `close()`，否则 server/SQLite/sweeper 会随进程继续占用资源；
- `close()` 后端口释放；
- 默认临时目录被删除，显式 `dataDir` 根据 `deleteDataOnClose` 决定。

## v1.3 item 约定

Embedded server 使用同一套通用 item：

```text
summary : 短摘要
content : 不透明完整内容
```

程序可以通过 `server.client()` 调用：

- `push`（summary/content）
- `view`（summary + content）
- `update` / `append` / `deleteItem`
- `claim` / `complete` 等处理状态

修改/追加/删除只允许 `available` item；claimed item 返回 409。修改会推进 revision，下一次 DSH 唤醒摘要会使用最新 summary。

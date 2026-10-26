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

## 自定义 data dir

```java
EmbeddedInboxOptions options = EmbeddedInboxOptions.builder()
    .dataDir(Path.of("/var/lib/my-program/inbox"))
    .inboxId("app-a")
    .heartbeat(Duration.ofSeconds(15))
    .deleteDataOnClose(false)
    .build();

try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start(options)) {
  // inbox.apiUrl() / inbox.mcpUrl() / inbox.provisionerToken()
}
```

显式给 `dataDir` 时默认不删除；只要临时目录时默认随 `close()` 删除。

## 拿到什么

```text
apiUrl()            http://127.0.0.1:<random>
mcpUrl()            http://127.0.0.1:<random>/mcp
inboxId()           app-a
provisionerToken()  一次返回的 exact-bound provisioner token
client()            已配置 apiUrl + token 的 InboxClient
dataDir()           当前实例数据目录
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

`inbox-embedded` 只绑 `127.0.0.1`：

- 同机其他进程可以连接返回的 `apiUrl()` / `mcpUrl()`；
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

# 部署（服务端）

> 本文件只讲**服务端怎么装、怎么升、怎么核**。协议契约见 `docs/inbox-push-contract.md`，
> HTTP/MCP 细节见 `docs/api/openapi.yaml`、`docs/upstream/**`。

## 1. 构建

JDK 21 + 仓内 Maven wrapper（系统 `mvn` 若是 3.8.x 会被 enforcer 拒）：

```bash
git clone git@github.com:c1589190/InboxMosire.git && cd InboxMosire
git checkout Alpha20261106
./mvnw -o -B -ntp -pl inbox-server -am package -DskipTests
# → inbox-server/target/inbox-server-0.1.0-SNAPSHOT-all.jar（约 19 MB，依赖全内置）
```

## 2. 初始化与凭据

```bash
java -jar inbox-server-*.jar init --data-dir /srv/inbox/data
java -jar inbox-server-*.jar token issue --data-dir /srv/inbox/data \
  --label iibot-bot --inbox iibot-bot \
  --scope items:write --scope items:read --scope items:manage \
  --scope events:read --scope status:read
```

token 只在签发时打印一次，落 0600 文件；不要写进 config、日志或聊天。
`items:manage` 是 **ack delivery** 需要的 scope（旧 token 没有它 → ack 会 403）。

## 3. 启动

```bash
java -jar inbox-server-*.jar serve --data-dir /srv/inbox/data \
  --api-port 46600 --mcp-port 46601 \
  --notify-threshold 200 \
  --important-delay-millis 1000 \
  --normal-delay-millis 30000 \
  --delivery-ttl-millis 600000 \
  --delivery-max-attempts 3 \
  --delivery-sweep-interval-millis 500
```

| 标志 | 默认 | 含义 |
|---|---|---|
| `--notify-threshold` | 200 | priority ≥ 该值算「重要」，走快档 |
| `--important-delay-millis` | 1000 | 重要消息合并窗口 |
| `--normal-delay-millis` | 30000 | 普通消息合并窗口 |
| `--delivery-ttl-millis` | 600000 | 未 ack 的 delivery 多久后重推 |
| `--delivery-max-attempts` | 3 | 重试上限，超出置 `undelivered`（item 不丢） |
| `--delivery-sweep-interval-millis` | 500 | 扫描间隔 |

启动自报一行：`Inbox 推送配置 threshold=200 importantDelay=1s normalDelay=30s ttl=600s maxAttempts=3 sweep=500ms`。

## 4. 从上一版升级

- 自动迁移：`schema_migration.version = 1 → 2`，新增 `inbox_delivery` 表与
  `inbox_event.event_type / delivery_id / data_json` 三列。
- **迁移前先复制 `inbox.db`**（WAL 模式下连 `-wal`/`-shm` 一起备，或先 `PRAGMA wal_checkpoint(TRUNCATE)`）。
- `content` 现在**必须是已渲染好的单个字符串**：服务端 HTTP/MCP/客户端三处都拒收非字符串
  （错误文案 `content 必须是已渲染好的单个字符串`）。旧代码若往里塞 JSON 对象，会直接 400。
- 旧的 `inbox.changed` 事件**照旧发**，适配器可随时退回旧路径。

## 5. 核对

```bash
# 1) 健康
curl -s localhost:46600/healthz
# 2) 推一条重要消息（priority ≥ 200），看 SSE
curl -sN localhost:46600/v1/inboxes/iibot-bot/events -H "Authorization: Bearer $TOKEN"
#   期望：event: inbox.push，type=io.mosire.inbox.push.v1，data.text 逐字、important=true
# 3) ack
curl -s -X POST localhost:46600/v1/inboxes/iibot-bot/deliveries/$DELIVERY_ID/ack \
     -H "Authorization: Bearer $TOKEN" -d '{}'
#   期望 acked:true；重复调用 acked:false（幂等）
# 4) 条目不丢
curl -s "localhost:46600/v1/inboxes/iibot-bot/items?status=done" -H "Authorization: Bearer $TOKEN"
```

## 6. 端口与部署形态

- 默认只监听回环；跨机部署请放在 SSH 隧道或反向代理后面，不要把 API 直接暴露到公网。
- 单进程单库；`inbox-embedded` 可在 Java 程序内一行启动（iiBot 的 `inbox.embedded=true` 用它）。

## 7. 故障

| 现象 | 原因 | 处理 |
|---|---|---|
| ack 返回 403 | token 缺 `items:manage` | 重新签发带该 scope 的 token |
| 一直只有 `inbox.changed` | priority < 阈值 | 检查 producer 写入的 priority，或调低 `--notify-threshold` |
| 重复推送同一条 | 未 ack 且 TTL 到 | 适配器应调 ack；查 `inbox_delivery.state` 是否 `undelivered` |
| 启动报 `content 必须是…` | 旧版 producer 塞了对象 | 让 producer 改传渲染好的字符串 |

# 验证

## 常用

```bash
./mvnw -B -ntp test
./mvnw -B -ntp verify
scripts/gate.sh focused inbox-server
scripts/gate.sh full
```

## 验收证据

- Maven 退出码；
- Surefire 测试计数；
- Checkstyle 0 violations；
- 真 HTTP/SSE 测试；
- 官方 MCP client 的 initialize/tools/list/tools/call；
- git status 与提交 hash；
- 安全审计：日志/SSE 不含 token 与正文。

当前完整门禁结果记录在 `docs/agents/STATUS.md`。

## v3 验证补充（2026-10-25）

- `./mvnw -B -ntp clean install`：30 tests，0 failures/errors/skipped；所有模块 Checkstyle 0。
- wake summary：`issueBinding()` token 含 `items:read`，可读取 `/items`；无 `items:read` 的 token 访问 `/items` 返回 403。
- occupancy：占用、status 可见、其他合法 token 仍可访问、wake token 清除、daemon 启动清空旧占用均通过。
- embedded：随机端口启动、自动创建 inbox、client 操作、close 清理临时目录、自定义 dataDir 保留均通过。
- 真实 live 实例：旧 SQLite schema 迁移、旧 token 兼容、共享 client provision/issue/descriptor、scope 403、SSE 隐私扫描均通过。
- 证据：`.work/live/logs/verification-v2.txt`、`.work/live/logs/verification-occupancy.txt`（本机，不入库）。

## 禁止

- `-DskipTests` 冒充通过；
- 只跑 test 声称完成 verify；
- 在未实际运行的情况下写“通过”。

## v1.4 验证补充（2026-10-26）

- `./mvnw -B -ntp clean install`：31 tests，0 failures/errors/skipped；7 个模块 Checkstyle 0。
- 中心 MCP tools/list：11 个，含 `inbox_view`、`inbox_update`、`inbox_append`、`inbox_delete`。
- 程序自有 MCP tools/list：15 个，包含 occupancy 三件套与 credential info。
- 真实 HTTP/MCP 进程内 daemon：
  - push summary/content；
  - view 不 claim；
  - update summary/content/priority；
  - append content；
  - delete 归档；
  - claimed item update 返回 409；
  - list 不含 content。
- 兼容：旧 `title/payload` 输入继续可用；`/items` 响应同时含 `summary` 与 `title`。
- 未启动 live daemon；未重跑真实 DSH host/ACP。
- DSH 当前 summary 基线：`8c2bdaa`；下一阶段提示词见 `docs/upstream/DSH-SUMMARY-CONTENT-PROMPT.md`。

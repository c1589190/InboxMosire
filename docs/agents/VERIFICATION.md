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

- `./mvnw -B -ntp clean install`：28 tests，0 failures/errors/skipped；所有模块 Checkstyle 0。
- occupancy：占用、status 可见、其他合法 token 仍可访问、wake token 清除、daemon 启动清空旧占用均通过。
- 真实 live 实例：旧 SQLite schema 迁移、旧 token 兼容、共享 client provision/issue/descriptor、scope 403、SSE 隐私扫描均通过。
- 证据：`.work/live/logs/verification-v2.txt`、`.work/live/logs/verification-occupancy.txt`（本机，不入库）。

## 禁止

- `-DskipTests` 冒充通过；
- 只跑 test 声称完成 verify；
- 在未实际运行的情况下写“通过”。

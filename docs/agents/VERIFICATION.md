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
- 真 HTTP/SSE 测试；
- 官方 MCP client 的 initialize/tools/list/tools/call；
- git status 与提交 hash；
- 安全审计：日志/SSE 不含 token 与正文。

## 禁止

- `-DskipTests` 冒充通过；
- 只跑 test 声称完成 verify；
- 在未实际运行的情况下写“通过”。

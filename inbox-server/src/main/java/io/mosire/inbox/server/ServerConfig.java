package io.mosire.inbox.server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;

public record ServerConfig(
    Path dataDir,
    String apiHost,
    int apiPort,
    String mcpHost,
    int mcpPort,
    String mcpPath,
    Duration sseHeartbeat,
    String defaultInbox) {

  public static ServerConfig defaults(Path dataDir) {
    return new ServerConfig(
        dataDir,
        "127.0.0.1",
        18765,
        "127.0.0.1",
        18766,
        "/mcp",
        Duration.ofSeconds(15),
        "main");
  }

  public ServerConfig {
    if (dataDir == null) {
      throw new IllegalArgumentException("dataDir 不能为空");
    }
    if (!isLoopback(apiHost)) {
      throw new IllegalArgumentException("v1 HTTP API 仅允许回环地址，拒绝绑定: " + apiHost);
    }
    if (!isLoopback(mcpHost)) {
      throw new IllegalArgumentException("v1 MCP 仅允许回环地址，拒绝绑定: " + mcpHost);
    }
    if (apiPort < 0 || apiPort > 65535 || mcpPort < 0 || mcpPort > 65535) {
      throw new IllegalArgumentException("端口必须在 0..65535");
    }
    if (mcpPath == null || !mcpPath.startsWith("/")) {
      throw new IllegalArgumentException("mcpPath 必须以 / 开头");
    }
    if (mcpPath.indexOf('?') >= 0 || mcpPath.indexOf('#') >= 0) {
      throw new IllegalArgumentException("mcpPath 不能包含 query 或 fragment");
    }
    if (sseHeartbeat == null || sseHeartbeat.isNegative() || sseHeartbeat.isZero()) {
      throw new IllegalArgumentException("heartbeat 必须为正");
    }
    if (defaultInbox == null || defaultInbox.isBlank()) {
      throw new IllegalArgumentException("defaultInbox 不能为空");
    }
  }

  public static boolean isLoopback(String host) {
    if (host == null || host.isBlank()) {
      return false;
    }
    if ("localhost".equalsIgnoreCase(host)) {
      return true;
    }
    try {
      return InetAddress.getByName(host).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }
}

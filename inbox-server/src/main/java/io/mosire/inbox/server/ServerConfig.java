package io.mosire.inbox.server;

import io.mosire.inbox.core.DeliveryPolicy;
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
    String defaultInbox,
    int notifyThreshold,
    Duration importantDelay,
    Duration normalDelay,
    Duration deliveryTtl,
    int deliveryMaxAttempts,
    Duration deliverySweepInterval) {

  public static ServerConfig defaults(Path dataDir) {
    return new ServerConfig(
        dataDir,
        "127.0.0.1",
        18765,
        "127.0.0.1",
        18766,
        "/mcp",
        Duration.ofSeconds(15),
        "main",
        DeliveryPolicy.DEFAULT_NOTIFY_THRESHOLD,
        DeliveryPolicy.DEFAULT_IMPORTANT_DELAY,
        DeliveryPolicy.DEFAULT_NORMAL_DELAY,
        DeliveryPolicy.DEFAULT_DELIVERY_TTL,
        DeliveryPolicy.DEFAULT_MAX_ATTEMPTS,
        Duration.ofMillis(500));
  }

  /** 兼容旧调用方：使用默认档位/推送配置。 */
  public ServerConfig(
      Path dataDir,
      String apiHost,
      int apiPort,
      String mcpHost,
      int mcpPort,
      String mcpPath,
      Duration sseHeartbeat,
      String defaultInbox) {
    this(
        dataDir,
        apiHost,
        apiPort,
        mcpHost,
        mcpPort,
        mcpPath,
        sseHeartbeat,
        defaultInbox,
        DeliveryPolicy.DEFAULT_NOTIFY_THRESHOLD,
        DeliveryPolicy.DEFAULT_IMPORTANT_DELAY,
        DeliveryPolicy.DEFAULT_NORMAL_DELAY,
        DeliveryPolicy.DEFAULT_DELIVERY_TTL,
        DeliveryPolicy.DEFAULT_MAX_ATTEMPTS,
        Duration.ofMillis(500));
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
    if (notifyThreshold < -1000 || notifyThreshold > 1000) {
      throw new IllegalArgumentException("notifyThreshold 必须在 -1000..1000");
    }
    requireNonNegative(importantDelay, "importantDelay");
    requireNonNegative(normalDelay, "normalDelay");
    requirePositive(deliveryTtl, "deliveryTtl");
    if (deliveryMaxAttempts < 1 || deliveryMaxAttempts > 100) {
      throw new IllegalArgumentException("deliveryMaxAttempts 必须在 1..100");
    }
    requirePositive(deliverySweepInterval, "deliverySweepInterval");
  }

  public DeliveryPolicy deliveryPolicy() {
    return new DeliveryPolicy(
        notifyThreshold, importantDelay, normalDelay, deliveryTtl, deliveryMaxAttempts);
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " 必须为正");
    }
  }

  private static void requireNonNegative(Duration value, String name) {
    if (value == null || value.isNegative()) {
      throw new IllegalArgumentException(name + " 不能为负");
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

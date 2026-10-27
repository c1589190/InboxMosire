package io.mosire.inbox.embedded;

import io.mosire.inbox.server.ServerConfig;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 嵌入式 server 的启动选项。
 *
 * <p>未显式传入 {@code dataDir} 时使用独立临时目录；网络默认只绑定
 * {@code 127.0.0.1}，端口 0 表示随机可用端口。
 */
public final class EmbeddedInboxOptions {
  private final Path dataDir;
  private final String inboxId;
  private final String apiHost;
  private final int apiPort;
  private final String mcpHost;
  private final int mcpPort;
  private final String mcpPath;
  private final Duration heartbeat;
  private final Duration claimSweepInterval;
  private final Boolean deleteDataOnClose;

  private EmbeddedInboxOptions(Builder builder) {
    this.dataDir = builder.dataDir;
    this.inboxId = builder.inboxId;
    this.apiHost = builder.apiHost;
    this.apiPort = builder.apiPort;
    this.mcpHost = builder.mcpHost;
    this.mcpPort = builder.mcpPort;
    this.mcpPath = builder.mcpPath;
    this.heartbeat = builder.heartbeat;
    this.claimSweepInterval = builder.claimSweepInterval;
    this.deleteDataOnClose = builder.deleteDataOnClose;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Path dataDir() {
    return dataDir;
  }

  public String inboxId() {
    return inboxId;
  }

  public String apiHost() {
    return apiHost;
  }

  public int apiPort() {
    return apiPort;
  }

  public String mcpHost() {
    return mcpHost;
  }

  public int mcpPort() {
    return mcpPort;
  }

  public String mcpPath() {
    return mcpPath;
  }

  public Duration heartbeat() {
    return heartbeat;
  }

  public Duration claimSweepInterval() {
    return claimSweepInterval;
  }

  public Boolean deleteDataOnClose() {
    return deleteDataOnClose;
  }

  public static final class Builder {
    private Path dataDir;
    private String inboxId = "main";
    private String apiHost = "127.0.0.1";
    private int apiPort;
    private String mcpHost = "127.0.0.1";
    private int mcpPort;
    private String mcpPath = "/mcp";
    private Duration heartbeat = Duration.ofSeconds(15);
    private Duration claimSweepInterval = Duration.ofSeconds(5);
    private Boolean deleteDataOnClose;

    private Builder() {}

    /** 数据目录；省略则使用独立临时目录。 */
    public Builder dataDir(Path value) {
      this.dataDir = value;
      return this;
    }

    /** 启动时自动 ensure 并作为默认 inbox 的 id。 */
    public Builder inboxId(String value) {
      this.inboxId = value;
      return this;
    }

    /** API 绑定 host；只允许回环地址。 */
    public Builder apiHost(String value) {
      this.apiHost = value;
      return this;
    }

    /** API 端口；0 表示随机可用端口。 */
    public Builder apiPort(int value) {
      this.apiPort = value;
      return this;
    }

    /** MCP 绑定 host；只允许回环地址。 */
    public Builder mcpHost(String value) {
      this.mcpHost = value;
      return this;
    }

    /** MCP 端口；0 表示随机可用端口。 */
    public Builder mcpPort(int value) {
      this.mcpPort = value;
      return this;
    }

    /** MCP path；必须以 / 开头且不能含 query/fragment。 */
    public Builder mcpPath(String value) {
      this.mcpPath = value;
      return this;
    }

    /** SSE heartbeat 周期，必须为正。 */
    public Builder heartbeat(Duration value) {
      this.heartbeat = value;
      return this;
    }

    /** claim sweeper 的初始延迟和周期，必须为正。 */
    public Builder claimSweepInterval(Duration value) {
      this.claimSweepInterval = value;
      return this;
    }

    /**
     * close 时是否删除 dataDir。省略时：临时目录删除，显式 dataDir 保留。
     */
    public Builder deleteDataOnClose(boolean value) {
      this.deleteDataOnClose = value;
      return this;
    }

    public EmbeddedInboxOptions build() {
      if (inboxId == null || inboxId.isBlank()) {
        throw new IllegalArgumentException("inboxId 不能为空");
      }
      requireHost("apiHost", apiHost);
      requireHost("mcpHost", mcpHost);
      requirePort("apiPort", apiPort);
      requirePort("mcpPort", mcpPort);
      requireMcpPath(mcpPath);
      requirePositive("heartbeat", heartbeat);
      requirePositive("claimSweepInterval", claimSweepInterval);
      return new EmbeddedInboxOptions(this);
    }

    private static void requireHost(String name, String value) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(name + " 不能为空");
      }
      if (!ServerConfig.isLoopback(value)) {
        throw new IllegalArgumentException(name + " 只允许回环地址: " + value);
      }
    }

    private static void requirePort(String name, int value) {
      if (value < 0 || value > 65535) {
        throw new IllegalArgumentException(name + " 必须在 0..65535");
      }
    }

    private static void requireMcpPath(String value) {
      if (value == null || !value.startsWith("/")) {
        throw new IllegalArgumentException("mcpPath 必须以 / 开头");
      }
      if (value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
        throw new IllegalArgumentException("mcpPath 不能包含 query 或 fragment");
      }
    }

    private static void requirePositive(String name, Duration value) {
      if (value == null || value.isNegative() || value.isZero()) {
        throw new IllegalArgumentException(name + " 必须为正");
      }
    }
  }
}

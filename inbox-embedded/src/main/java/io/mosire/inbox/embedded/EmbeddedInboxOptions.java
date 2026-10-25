package io.mosire.inbox.embedded;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** 嵌入式 server 的启动选项。dataDir 为 null 时使用进程临时目录。 */
public final class EmbeddedInboxOptions {
  private final Path dataDir;
  private final String inboxId;
  private final Duration heartbeat;
  private final Boolean deleteDataOnClose;

  private EmbeddedInboxOptions(Builder builder) {
    this.dataDir = builder.dataDir;
    this.inboxId = builder.inboxId;
    this.heartbeat = builder.heartbeat;
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

  public Duration heartbeat() {
    return heartbeat;
  }

  public Boolean deleteDataOnClose() {
    return deleteDataOnClose;
  }

  public static final class Builder {
    private Path dataDir;
    private String inboxId = "main";
    private Duration heartbeat = Duration.ofSeconds(15);
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

    public Builder heartbeat(Duration value) {
      this.heartbeat = value;
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
      Objects.requireNonNull(heartbeat, "heartbeat");
      return new EmbeddedInboxOptions(this);
    }
  }
}

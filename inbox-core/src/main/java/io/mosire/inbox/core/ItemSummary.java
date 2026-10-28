package io.mosire.inbox.core;

import java.time.Instant;

/** 选择通知时可见的轻量摘要；content 只在 view/claim 后返回。 */
public record ItemSummary(
    String itemId,
    String channel,
    String kind,
    String summary,
    int priority,
    int attempts,
    Instant createdAt,
    String source) {
  /** 兼容旧调用方；source 为 null。 */
  public ItemSummary(
      String itemId,
      String channel,
      String kind,
      String summary,
      int priority,
      int attempts,
      Instant createdAt) {
    this(itemId, channel, kind, summary, priority, attempts, createdAt, null);
  }

  /** 兼容旧调用方的只读别名；新代码使用 summary()。 */
  @Deprecated
  public String title() {
    return summary;
  }
}

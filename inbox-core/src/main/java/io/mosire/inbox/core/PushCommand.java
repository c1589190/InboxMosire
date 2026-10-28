package io.mosire.inbox.core;

import java.time.Instant;

/** 新建通知的领域输入：summary 是短摘要，content 是程序/用户自定义的不透明内容。 */
public record PushCommand(
    String inboxId,
    String channel,
    String kind,
    String summary,
    String content,
    String contentRef,
    String dedupeKey,
    int priority,
    Instant availableAt,
    String source) {
  /** 兼容旧调用方；source 为 null。 */
  public PushCommand(
      String inboxId,
      String channel,
      String kind,
      String summary,
      String content,
      String contentRef,
      String dedupeKey,
      int priority,
      Instant availableAt) {
    this(
        inboxId,
        channel,
        kind,
        summary,
        content,
        contentRef,
        dedupeKey,
        priority,
        availableAt,
        null);
  }
}

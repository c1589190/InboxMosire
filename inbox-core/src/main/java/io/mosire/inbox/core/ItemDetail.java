package io.mosire.inbox.core;

import java.time.Instant;

/** 单条通知的完整视图；content 是不透明文本，不解释为工具调用。 */
public record ItemDetail(
    String itemId,
    String inboxId,
    String channel,
    String kind,
    String summary,
    String content,
    String contentRef,
    int priority,
    ItemStatus status,
    int attempts,
    long revision,
    Instant createdAt,
    Instant updatedAt) {}

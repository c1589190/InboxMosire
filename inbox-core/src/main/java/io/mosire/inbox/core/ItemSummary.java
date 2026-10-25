package io.mosire.inbox.core;

import java.time.Instant;

/** 选择通知时可见的轻量摘要；payload 只有在 claim 后才返回。 */
public record ItemSummary(
    String itemId,
    String channel,
    String kind,
    String title,
    int priority,
    int attempts,
    Instant createdAt) {}

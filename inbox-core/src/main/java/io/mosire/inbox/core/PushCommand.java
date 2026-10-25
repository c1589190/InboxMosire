package io.mosire.inbox.core;

import java.time.Instant;

/** 新建通知的领域输入。payload 是调用方提供的原始 JSON 文本或普通文本。 */
public record PushCommand(
    String inboxId,
    String channel,
    String kind,
    String title,
    String payload,
    String payloadRef,
    String dedupeKey,
    int priority,
    Instant availableAt) {}

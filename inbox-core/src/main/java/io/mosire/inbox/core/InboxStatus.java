package io.mosire.inbox.core;

import java.time.Instant;
import java.util.Map;

/** 适配器/Agent 观察通知板的真相快照。不包含正文。 */
public record InboxStatus(
    String inboxId,
    long revision,
    long available,
    long claimed,
    Map<String, ChannelCounts> channels,
    Instant updatedAt) {}

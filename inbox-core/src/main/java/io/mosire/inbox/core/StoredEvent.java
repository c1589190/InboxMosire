package io.mosire.inbox.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** outbox 中的事实快照；只含路由元数据，不含通知正文。 */
public record StoredEvent(
    long seq,
    String eventId,
    String inboxId,
    long revision,
    long available,
    long claimed,
    Map<String, ChannelCounts> channels,
    List<String> changedChannels,
    Instant createdAt) {}

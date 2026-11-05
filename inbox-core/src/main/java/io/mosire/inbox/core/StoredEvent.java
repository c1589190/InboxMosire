package io.mosire.inbox.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * outbox 中的事实快照。changed 事件只含路由元数据；push 事件的正文放在 {@link #data()} 的
 * text 字段中，绝不进入字段名之外的任何元数据。
 */
public record StoredEvent(
    long seq,
    String eventId,
    String inboxId,
    long revision,
    long available,
    long claimed,
    Map<String, ChannelCounts> channels,
    List<String> changedChannels,
    Instant createdAt,
    String eventType,
    String deliveryId,
    Map<String, Object> data) {

  /** 兼容旧调用方：默认是计数型 changed 事件。 */
  public StoredEvent(
      long seq,
      String eventId,
      String inboxId,
      long revision,
      long available,
      long claimed,
      Map<String, ChannelCounts> channels,
      List<String> changedChannels,
      Instant createdAt) {
    this(
        seq,
        eventId,
        inboxId,
        revision,
        available,
        claimed,
        channels,
        changedChannels,
        createdAt,
        InboxEventTypes.CHANGED,
        null,
        null);
  }

  public boolean isPush() {
    return InboxEventTypes.PUSH.equals(eventType);
  }
}

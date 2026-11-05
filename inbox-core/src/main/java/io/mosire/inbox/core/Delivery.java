package io.mosire.inbox.core;

import java.time.Instant;
import java.util.List;

/** 一条已落库的推送记录（只用于诊断/测试）。 */
public record Delivery(
    String deliveryId,
    String inboxId,
    List<String> itemIds,
    boolean important,
    String text,
    List<DeliveryAttachment> attachments,
    Instant createdAt,
    Instant ackedAt,
    int attempts,
    String state) {}

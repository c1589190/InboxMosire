package io.mosire.inbox.core;

import java.time.Instant;

public record ClaimedItem(
    String itemId,
    String channel,
    String kind,
    String title,
    String payload,
    String payloadRef,
    int priority,
    String claimToken,
    Instant claimExpiresAt) {}

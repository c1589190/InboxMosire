package io.mosire.inbox.core;

import java.time.Instant;

/** 占用是 advisory 状态，不是访问控制；token 仍是唯一访问边界。 */
public record InboxOccupancy(
    String inboxId, boolean occupied, String occupiedBy, Instant occupiedAt, Instant updatedAt) {}

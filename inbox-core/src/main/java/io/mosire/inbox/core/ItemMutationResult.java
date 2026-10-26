package io.mosire.inbox.core;

import java.time.Instant;

public record ItemMutationResult(
    String itemId, long revision, long available, Instant updatedAt, boolean deleted) {}

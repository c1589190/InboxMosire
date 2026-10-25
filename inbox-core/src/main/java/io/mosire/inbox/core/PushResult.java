package io.mosire.inbox.core;

import java.time.Instant;

public record PushResult(String itemId, boolean duplicate, long revision, Instant createdAt) {}

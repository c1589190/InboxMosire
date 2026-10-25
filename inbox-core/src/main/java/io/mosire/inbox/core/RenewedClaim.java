package io.mosire.inbox.core;

import java.time.Instant;

public record RenewedClaim(String claimToken, String itemId, Instant claimExpiresAt) {}

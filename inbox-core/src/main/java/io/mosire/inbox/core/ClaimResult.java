package io.mosire.inbox.core;

import java.util.List;

public record ClaimResult(
    long revision,
    long available,
    List<ClaimedItem> claimed,
    List<ClaimRejection> rejected) {}

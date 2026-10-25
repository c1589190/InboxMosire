package io.mosire.inbox.core;

import java.util.List;

public record ReleaseResult(
    long revision, long available, List<String> releasedItemIds, List<ClaimRejection> rejected) {}

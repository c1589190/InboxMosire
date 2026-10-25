package io.mosire.inbox.core;

import java.util.List;

public record CompleteResult(
    long revision,
    long available,
    List<CompletionAck> completed,
    List<ClaimRejection> rejected) {}

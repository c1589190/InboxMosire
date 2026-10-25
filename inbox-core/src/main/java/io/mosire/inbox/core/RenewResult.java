package io.mosire.inbox.core;

import java.util.List;

public record RenewResult(List<RenewedClaim> renewed, List<ClaimRejection> rejected) {}

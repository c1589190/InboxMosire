package io.mosire.inbox.core;

public record CompletionAck(String itemId, CompletionOutcome outcome, boolean idempotent) {}

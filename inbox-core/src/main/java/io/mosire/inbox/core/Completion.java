package io.mosire.inbox.core;

public record Completion(String claimToken, CompletionOutcome outcome, String summary) {}

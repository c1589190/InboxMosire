package io.mosire.inbox.sqlite;

import java.time.Instant;
import java.util.Set;

/** 已认证/可管理的本地 API credential metadata；永远不包含 token 原文或 hash。 */
public record ApiCredential(
    String id,
    String label,
    Set<String> scopes,
    String inboxId,
    Set<String> channels,
    String fingerprint,
    Instant createdAt,
    Instant expiresAt,
    Instant lastUsedAt,
    Instant revokedAt,
    String rotatedFrom,
    String createdBy) {

  public boolean hasScope(String scope) {
    return scopes.contains(scope);
  }

  public boolean allowsInbox(String requestedInbox) {
    return "*".equals(inboxId) || inboxId.equals(requestedInbox);
  }

  public boolean allowsChannel(String requestedChannel) {
    return channels.isEmpty() || channels.contains("*") || channels.contains(requestedChannel);
  }

  public boolean active(Instant now) {
    return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
  }
}

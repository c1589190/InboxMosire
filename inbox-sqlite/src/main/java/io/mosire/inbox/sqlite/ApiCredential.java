package io.mosire.inbox.sqlite;

import java.util.Set;

/** 已认证的本地 API credential；不持有 token 原文。 */
public record ApiCredential(
    String id, String label, Set<String> scopes, String inboxId, Set<String> channels) {
  public boolean hasScope(String scope) {
    return scopes.contains(scope);
  }

  public boolean allowsInbox(String requestedInbox) {
    return "*".equals(inboxId) || inboxId.equals(requestedInbox);
  }

  public boolean allowsChannel(String requestedChannel) {
    return channels.isEmpty() || channels.contains("*") || channels.contains(requestedChannel);
  }
}

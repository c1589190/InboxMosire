package io.mosire.inbox.client;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 共享客户端的公开 JSON 形状；字段名与 HTTP/MCP v1 契约一致。 */
public final class InboxModels {
  private InboxModels() {}

  public record EnsureResult(String inbox, boolean created) {}

  public record ChannelCounts(long available, long claimed) {}

  public record InboxStatus(
      String inbox,
      long revision,
      long available,
      long claimed,
      Map<String, ChannelCounts> channels,
      String updatedAt,
      boolean occupied,
      String occupiedBy,
      String occupiedAt) {}

  public record Occupancy(
      String inboxId,
      boolean occupied,
      String occupiedBy,
      String occupiedAt,
      String updatedAt) {}

  public record ItemSummary(
      String itemId,
      String channel,
      String kind,
      String title,
      int priority,
      int attempts,
      String createdAt) {}

  public record ItemList(String inbox, List<ItemSummary> items, int count) {}

  public record PushItem(
      String channel,
      String kind,
      String title,
      Object payload,
      String payloadRef,
      String dedupeKey,
      int priority) {
    public static Builder builder(String channel) {
      return new Builder(channel);
    }

    public static final class Builder {
      private final String channel;
      private String kind = "notification";
      private String title;
      private Object payload;
      private String payloadRef;
      private String dedupeKey;
      private int priority;

      private Builder(String channel) {
        this.channel = channel;
      }

      public Builder kind(String value) {
        this.kind = value;
        return this;
      }

      public Builder title(String value) {
        this.title = value;
        return this;
      }

      public Builder payload(Object value) {
        this.payload = value;
        return this;
      }

      public Builder payloadRef(String value) {
        this.payloadRef = value;
        return this;
      }

      public Builder dedupeKey(String value) {
        this.dedupeKey = value;
        return this;
      }

      public Builder priority(int value) {
        this.priority = value;
        return this;
      }
      public PushItem build() {
        return new PushItem(channel, kind, title, payload, payloadRef, dedupeKey, priority);
      }
    }
  }

  public record PushResult(String itemId, boolean duplicate, long revision, String createdAt) {}

  public record ClaimedItem(
      String itemId,
      String channel,
      String kind,
      String title,
      Object payload,
      String payloadRef,
      int priority,
      String claimToken,
      String claimExpiresAt) {}

  public record Rejection(String subject, String code, String message) {}

  public record ClaimResult(
      long revision,
      long available,
      List<ClaimedItem> claimed,
      List<Rejection> rejected) {}

  public record RenewedClaim(String claimToken, String itemId, String claimExpiresAt) {}

  public record RenewResult(List<RenewedClaim> renewed, List<Rejection> rejected) {}

  public record Completion(String claimToken, String outcome, String summary) {}

  public record CompletionAck(String itemId, String outcome, boolean idempotent) {}

  public record CompleteResult(
      long revision,
      long available,
      List<CompletionAck> completed,
      List<Rejection> rejected) {}

  public record ReleaseResult(
      long revision,
      long available,
      List<String> releasedItemIds,
      List<Rejection> rejected) {}

  public record CredentialIssueRequest(
      String label, Set<String> scopes, Set<String> channels, Long expiresInSeconds) {}

  public record CredentialView(
      String id,
      String label,
      Set<String> scopes,
      String inbox,
      Set<String> channels,
      String fingerprint,
      String createdAt,
      String expiresAt,
      String lastUsedAt,
      String revokedAt,
      String rotatedFrom,
      String createdBy) {}

  public record IssuedCredentialView(CredentialView credential, String token) {}

  public record CredentialList(List<CredentialView> credentials) {}

  public record RevokeResult(String id, boolean revoked) {}

  public record BindingDescriptor(
      String inboxId,
      String apiBaseUrl,
      String adapterTokenRef,
      Set<String> channels,
      String label) {}
}

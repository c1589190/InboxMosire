package io.mosire.inbox.core;

import java.time.Instant;

public record ClaimedItem(
    String itemId,
    String channel,
    String kind,
    String summary,
    String content,
    String contentRef,
    int priority,
    String claimToken,
    Instant claimExpiresAt) {
  /** @deprecated use summary() */
  @Deprecated
  public String title() {
    return summary;
  }

  /** @deprecated use content() */
  @Deprecated
  public String payload() {
    return content;
  }

  /** @deprecated use contentRef() */
  @Deprecated
  public String payloadRef() {
    return contentRef;
  }
}

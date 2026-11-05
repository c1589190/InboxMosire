package io.mosire.inbox.core;

import java.util.Objects;

/** 推送附件只允许引用，不含二进制。 */
public record DeliveryAttachment(String name, String ref) {
  public DeliveryAttachment {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(ref, "ref");
  }
}

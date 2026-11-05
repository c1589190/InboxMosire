package io.mosire.inbox.core;

import java.util.List;

/** ack 结果；重复 ack 时 acked=false 且 closedItems 为空。 */
public record DeliveryAck(
    String deliveryId,
    boolean acked,
    List<String> closedItems,
    int remaining,
    int remainingImportant) {
  public DeliveryAck {
    closedItems = closedItems == null ? List.of() : List.copyOf(closedItems);
  }
}

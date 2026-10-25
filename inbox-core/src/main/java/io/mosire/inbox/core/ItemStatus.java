package io.mosire.inbox.core;

/** 活跃通知只可能是 available/claimed；done 是归档态。 */
public enum ItemStatus {
  AVAILABLE("available"),
  CLAIMED("claimed"),
  DONE("done");

  private final String wireValue;

  ItemStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public static ItemStatus fromWire(String value) {
    for (ItemStatus status : values()) {
      if (status.wireValue.equals(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("未知 item 状态: " + value);
  }
}

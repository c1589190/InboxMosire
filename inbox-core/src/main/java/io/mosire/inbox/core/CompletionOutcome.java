package io.mosire.inbox.core;

/** complete 的结果语义。两者都离开活跃列表，区别用于审计和生产者重试判断。 */
public enum CompletionOutcome {
  COMPLETED("completed"),
  FAILED("failed");

  private final String wireValue;

  CompletionOutcome(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public static CompletionOutcome fromWire(String value) {
    for (CompletionOutcome outcome : values()) {
      if (outcome.wireValue.equals(value)) {
        return outcome;
      }
    }
    throw new IllegalArgumentException("未知 completion outcome: " + value);
  }
}

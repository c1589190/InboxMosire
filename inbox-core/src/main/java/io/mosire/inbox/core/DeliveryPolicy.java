package io.mosire.inbox.core;

import java.time.Duration;
import java.util.Objects;

/**
 * 档位与差序推送配置。唯一档位判据是 {@code priority >= notifyThreshold}。
 *
 * @param notifyThreshold priority 达到该值即视为重要
 * @param importantDelay 重要 item 入队后到首次推送的等待
 * @param normalDelay 普通 item 入队后的合并窗口
 * @param deliveryTtl 未 ack delivery 的重推间隔
 * @param maxAttempts 单条 delivery 最多推送次数（含首推）
 */
public record DeliveryPolicy(
    int notifyThreshold,
    Duration importantDelay,
    Duration normalDelay,
    Duration deliveryTtl,
    int maxAttempts) {

  public static final int DEFAULT_NOTIFY_THRESHOLD = 200;
  public static final Duration DEFAULT_IMPORTANT_DELAY = Duration.ofMillis(1000);
  public static final Duration DEFAULT_NORMAL_DELAY = Duration.ofMillis(30000);
  public static final Duration DEFAULT_DELIVERY_TTL = Duration.ofMillis(600000);
  public static final int DEFAULT_MAX_ATTEMPTS = 3;

  public static DeliveryPolicy defaults() {
    return new DeliveryPolicy(
        DEFAULT_NOTIFY_THRESHOLD,
        DEFAULT_IMPORTANT_DELAY,
        DEFAULT_NORMAL_DELAY,
        DEFAULT_DELIVERY_TTL,
        DEFAULT_MAX_ATTEMPTS);
  }

  public DeliveryPolicy {
    if (notifyThreshold < -1000 || notifyThreshold > 1000) {
      throw new IllegalArgumentException("notifyThreshold 必须在 -1000..1000");
    }
    requireNonNegative(importantDelay, "importantDelay");
    requireNonNegative(normalDelay, "normalDelay");
    if (deliveryTtl == null || deliveryTtl.isNegative() || deliveryTtl.isZero()) {
      throw new IllegalArgumentException("deliveryTtl 必须为正");
    }
    if (maxAttempts < 1 || maxAttempts > 100) {
      throw new IllegalArgumentException("maxAttempts 必须在 1..100");
    }
  }

  public boolean important(int priority) {
    return priority >= notifyThreshold;
  }

  private static void requireNonNegative(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isNegative()) {
      throw new IllegalArgumentException(name + " 不能为负");
    }
  }
}

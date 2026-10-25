package io.mosire.inbox.core;

import java.util.Objects;

/** 稳定的领域错误。HTTP/MCP 适配层只按 {@link Code} 映射，不解析中文文案。 */
public final class InboxException extends RuntimeException {
  public enum Code {
    INVALID_ARGUMENT,
    NOT_FOUND,
    CONFLICT,
    UNAVAILABLE,
    CLAIM_INVALID,
    CLAIM_EXPIRED,
    INTERNAL
  }

  private final Code code;

  public InboxException(Code code, String message) {
    super(Objects.requireNonNull(message, "message"));
    this.code = Objects.requireNonNull(code, "code");
  }

  public InboxException(Code code, String message, Throwable cause) {
    super(Objects.requireNonNull(message, "message"), cause);
    this.code = Objects.requireNonNull(code, "code");
  }

  public Code code() {
    return code;
  }
}

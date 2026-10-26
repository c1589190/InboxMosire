package io.mosire.inbox.core;

import java.util.Map;
import java.util.Objects;

/** 稳定的领域错误。HTTP/MCP 适配层只按 {@link Code} 映射，不解析中文文案。 */
public final class InboxException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public enum Code {
    INVALID_ARGUMENT,
    NOT_FOUND,
    CONFLICT,
    ITEM_STATUS_CONFLICT,
    ITEM_REVISION_CONFLICT,
    UNAVAILABLE,
    CLAIM_INVALID,
    CLAIM_EXPIRED,
    INTERNAL
  }

  private final Code code;
  private final Map<String, Object> details;

  public InboxException(Code code, String message) {
    this(code, message, Map.of(), null);
  }

  public InboxException(Code code, String message, Map<String, Object> details) {
    this(code, message, details, null);
  }

  public InboxException(Code code, String message, Throwable cause) {
    this(code, message, Map.of(), cause);
  }

  public InboxException(
      Code code, String message, Map<String, Object> details, Throwable cause) {
    super(Objects.requireNonNull(message, "message"), cause);
    this.code = Objects.requireNonNull(code, "code");
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }

  public Code code() {
    return code;
  }

  /** 安全的机器可读细节；不得包含 token、payload 或正文。 */
  public Map<String, Object> details() {
    return details;
  }
}

package io.mosire.inbox.client;

import java.util.Map;

/** 网络/HTTP/API 错误的稳定封装；message 不含 token 或 payload。 */
public final class InboxClientException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final int statusCode;
  private final String code;
  private final Map<String, Object> details;

  public InboxClientException(int statusCode, String code, String message) {
    this(statusCode, code, message, Map.of());
  }

  public InboxClientException(
      int statusCode, String code, String message, Map<String, Object> details) {
    super(message == null || message.isBlank() ? code : message);
    this.statusCode = statusCode;
    this.code = code;
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }

  public InboxClientException(String code, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = 0;
    this.code = code;
    this.details = Map.of();
  }

  public int statusCode() {
    return statusCode;
  }

  public String code() {
    return code;
  }

  /** 服务端返回的安全机器可读细节；不得包含 token、payload 或正文。 */
  public Map<String, Object> details() {
    return details;
  }
}

package io.mosire.inbox.client;

/** 网络/HTTP/API 错误的稳定封装；message 不含 token 或 payload。 */
public final class InboxClientException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final int statusCode;
  private final String code;

  public InboxClientException(int statusCode, String code, String message) {
    super(message == null || message.isBlank() ? code : message);
    this.statusCode = statusCode;
    this.code = code;
  }

  public InboxClientException(String code, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = 0;
    this.code = code;
  }

  public int statusCode() {
    return statusCode;
  }

  public String code() {
    return code;
  }
}

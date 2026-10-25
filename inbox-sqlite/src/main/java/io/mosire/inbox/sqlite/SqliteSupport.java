package io.mosire.inbox.sqlite;

import io.mosire.inbox.core.InboxException;

final class SqliteSupport {
  private SqliteSupport() {}

  static InboxException invalid(String message) {
    return new InboxException(InboxException.Code.INVALID_ARGUMENT, message);
  }

  static InboxException notFound(String message) {
    return new InboxException(InboxException.Code.NOT_FOUND, message);
  }

  static InboxException internal(String message, Throwable cause) {
    return new InboxException(InboxException.Code.INTERNAL, message, cause);
  }
}

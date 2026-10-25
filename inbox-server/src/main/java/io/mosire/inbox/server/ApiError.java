package io.mosire.inbox.server;

import java.util.Map;

record ApiError(String code, String message) {
  Map<String, Object> body() {
    return Map.of("error", Map.of("code", code, "message", message));
  }
}

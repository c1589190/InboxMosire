package io.mosire.inbox.server;

import java.util.LinkedHashMap;
import java.util.Map;

record ApiError(String code, String message, Map<String, Object> details) {
  ApiError(String code, String message) {
    this(code, message, Map.of());
  }

  Map<String, Object> body() {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("code", code);
    error.put("message", message);
    if (details != null && !details.isEmpty()) {
      error.put("details", details);
    }
    return Map.of("error", error);
  }
}

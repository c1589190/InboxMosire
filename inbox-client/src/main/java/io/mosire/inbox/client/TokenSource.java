package io.mosire.inbox.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** token 来源：显式值、环境变量或本地 secret 文件；每次请求时读取，支持轮换。 */
@FunctionalInterface
public interface TokenSource {
  String readToken();

  static TokenSource literal(String token) {
    String value = Objects.requireNonNull(token, "token");
    return () -> value;
  }

  static TokenSource env(String name) {
    return () -> {
      String value = System.getenv(name);
      if (value == null || value.isBlank()) {
        throw new InboxClientException("TOKEN_MISSING", "环境变量未配置: " + name, null);
      }
      return value.trim();
    };
  }

  static TokenSource file(Path path) {
    Objects.requireNonNull(path, "path");
    return () -> {
      try {
        String token = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (token.isBlank()) {
          throw new InboxClientException("TOKEN_MISSING", "token 文件为空: " + path, null);
        }
        return token;
      } catch (IOException e) {
        throw new InboxClientException("TOKEN_UNREADABLE", "token 文件不可读: " + path, e);
      }
    };
  }
}

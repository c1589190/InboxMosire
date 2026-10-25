package io.mosire.inbox.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Objects;

/** 程序本机 token 文件存储；原子写入并尽量设置 0600。 */
public final class TokenStore {
  private TokenStore() {}

  public static void writeAtomic(Path path, String token) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(token, "token");
    try {
      Path absolute = path.toAbsolutePath().normalize();
      Path parent = absolute.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path tempParent = parent == null ? Path.of(".").toAbsolutePath() : parent;
      Path tmp = Files.createTempFile(tempParent, ".inbox-token-", ".tmp");
      try {
        Files.writeString(tmp, token, StandardCharsets.UTF_8);
        setOwnerOnly(tmp);
        try {
          Files.move(
              tmp,
              absolute,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
          Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
        setOwnerOnly(absolute);
      } finally {
        Files.deleteIfExists(tmp);
      }
    } catch (IOException e) {
      throw new InboxClientException("TOKEN_STORE_WRITE_FAILED", "无法写入 token 文件: " + path, e);
    }
  }

  public static String read(Path path) {
    Objects.requireNonNull(path, "path");
    try {
      return Files.readString(path, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new InboxClientException("TOKEN_STORE_READ_FAILED", "无法读取 token 文件: " + path, e);
    }
  }

  private static void setOwnerOnly(Path path) {
    try {
      Files.setPosixFilePermissions(
          path,
          EnumSet.of(
              PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException | IOException ignored) {
      // 非 POSIX 文件系统保留平台默认权限。
    }
  }
}

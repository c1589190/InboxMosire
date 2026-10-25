package io.mosire.inbox.sqlite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** 本地 bearer credential 存储。数据库只保存 token 的 SHA-256 hash。 */
public final class SqliteApiCredentialStore {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private final Connection connection;
  private final ReentrantLock lock;

  SqliteApiCredentialStore(Connection connection, ReentrantLock lock) {
    this.connection = connection;
    this.lock = lock;
  }

  void initialize() {
    lock.lock();
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            CREATE TABLE IF NOT EXISTS api_credential (
              id          TEXT PRIMARY KEY,
              label       TEXT NOT NULL,
              token_hash  TEXT NOT NULL UNIQUE,
              scopes      TEXT NOT NULL,
              inbox_id    TEXT NOT NULL,
              channels    TEXT NOT NULL,
              created_at  TEXT NOT NULL,
              revoked_at  TEXT
            )
            """)) {
      ps.execute();
    } catch (SQLException e) {
      throw SqliteSupport.internal("初始化 api_credential 失败", e);
    } finally {
      lock.unlock();
    }
  }

  public IssuedCredential issue(
      String label, Set<String> scopes, String inboxId, Set<String> channels) {
    if (label == null || label.isBlank() || label.length() > 128) {
      throw SqliteSupport.invalid("label 必须为 1..128 字符");
    }
    if (scopes == null || scopes.isEmpty()) {
      throw SqliteSupport.invalid("至少需要一个 scope");
    }
    if (inboxId == null || inboxId.isBlank() || inboxId.length() > 64) {
      throw SqliteSupport.invalid("inboxId 必须为 1..64 字符");
    }
    Set<String> safeScopes = normalize(scopes);
    Set<String> safeChannels = channels == null ? Set.of() : normalize(channels);
    String token = "inbox_" + randomToken();
    String hash = sha256(token);
    String id = "cred_" + UUID.randomUUID();
    Instant now = Instant.now();
    ApiCredential credential =
        new ApiCredential(id, label, safeScopes, inboxId, safeChannels);
    lock.lock();
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO api_credential
              (id, label, token_hash, scopes, inbox_id, channels, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
      ps.setString(1, id);
      ps.setString(2, label);
      ps.setString(3, hash);
      ps.setString(4, String.join(",", safeScopes));
      ps.setString(5, inboxId);
      ps.setString(6, String.join(",", safeChannels));
      ps.setString(7, now.toString());
      ps.executeUpdate();
      return new IssuedCredential(credential, token);
    } catch (SQLException e) {
      throw SqliteSupport.internal("签发 credential 失败", e);
    } finally {
      lock.unlock();
    }
  }

  public Optional<ApiCredential> authenticate(String token) {
    if (token == null || token.isBlank() || token.length() > 512) {
      return Optional.empty();
    }
    String hash = sha256(token);
    lock.lock();
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            SELECT id, label, scopes, inbox_id, channels
            FROM api_credential
            WHERE token_hash = ? AND revoked_at IS NULL
            """)) {
      ps.setString(1, hash);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new ApiCredential(
                rs.getString("id"),
                rs.getString("label"),
                parseSet(rs.getString("scopes")),
                rs.getString("inbox_id"),
                parseSet(rs.getString("channels"))));
      }
    } catch (SQLException e) {
      throw SqliteSupport.internal("认证 credential 失败", e);
    } finally {
      lock.unlock();
    }
  }

  public boolean revoke(String credentialId) {
    lock.lock();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "UPDATE api_credential SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")) {
      ps.setString(1, Instant.now().toString());
      ps.setString(2, credentialId);
      return ps.executeUpdate() == 1;
    } catch (SQLException e) {
      throw SqliteSupport.internal("撤销 credential 失败", e);
    } finally {
      lock.unlock();
    }
  }

  private static Set<String> normalize(Set<String> input) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String value : input) {
      if (value == null || value.isBlank() || value.contains(",")) {
        throw SqliteSupport.invalid("scope/channel 不能为空且不能包含逗号");
      }
      out.add(value.trim());
    }
    return Set.copyOf(out);
  }

  private static Set<String> parseSet(String value) {
    if (value == null || value.isBlank()) {
      return Set.of();
    }
    return Set.copyOf(Arrays.asList(value.split(",")));
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return B64.encodeToString(bytes);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 缺少 SHA-256", e);
    }
  }
}

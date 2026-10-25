package io.mosire.inbox.sqlite;

import io.mosire.inbox.core.InboxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 本地 bearer credential 存储。数据库只保存 token 的 SHA-256 hash 与短 fingerprint。 */
public final class SqliteApiCredentialStore {
  private static final Logger LOG = LoggerFactory.getLogger(SqliteApiCredentialStore.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Duration LAST_USED_THROTTLE = Duration.ofSeconds(60);

  private final Connection connection;
  private final ReentrantLock lock;

  SqliteApiCredentialStore(Connection connection, ReentrantLock lock) {
    this.connection = connection;
    this.lock = lock;
  }

  void initialize() {
    lock.lock();
    try (Statement st = connection.createStatement()) {
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS api_credential (
            id           TEXT PRIMARY KEY,
            label        TEXT NOT NULL,
            token_hash   TEXT NOT NULL UNIQUE,
            scopes       TEXT NOT NULL,
            inbox_id     TEXT NOT NULL,
            channels     TEXT NOT NULL,
            fingerprint  TEXT NOT NULL DEFAULT '',
            created_at   TEXT NOT NULL,
            expires_at   TEXT,
            last_used_at TEXT,
            revoked_at   TEXT,
            rotated_from TEXT,
            created_by   TEXT
          )
          """);
      ensureColumn(st, "fingerprint", "TEXT NOT NULL DEFAULT ''");
      ensureColumn(st, "expires_at", "TEXT");
      ensureColumn(st, "last_used_at", "TEXT");
      ensureColumn(st, "rotated_from", "TEXT");
      ensureColumn(st, "created_by", "TEXT");
      st.execute(
          "UPDATE api_credential SET fingerprint = substr(token_hash, 1, 16) "
              + "WHERE fingerprint IS NULL OR fingerprint = ''");
      st.execute("CREATE INDEX IF NOT EXISTS idx_credential_inbox ON api_credential(inbox_id)");
    } catch (SQLException e) {
      throw SqliteSupport.internal("初始化 api_credential 失败", e);
    } finally {
      lock.unlock();
    }
  }

  public IssuedCredential issue(
      String label, Set<String> scopes, String inboxId, Set<String> channels) {
    return issue(label, scopes, inboxId, channels, null, null);
  }

  public IssuedCredential issue(
      String label,
      Set<String> scopes,
      String inboxId,
      Set<String> channels,
      Instant expiresAt,
      String createdBy) {
    validateIssue(label, scopes, inboxId);
    Set<String> safeScopes = normalize(scopes);
    Set<String> safeChannels = channels == null ? Set.of() : normalize(channels);
    String token = "inbox_" + randomToken();
    String hash = sha256(token);
    String id = "cred_" + UUID.randomUUID();
    Instant now = Instant.now();
    ApiCredential credential =
        new ApiCredential(
            id,
            label,
            safeScopes,
            inboxId,
            safeChannels,
            fingerprint(hash),
            now,
            expiresAt,
            null,
            null,
            null,
            createdBy);
    lock.lock();
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO api_credential
              (id, label, token_hash, scopes, inbox_id, channels, fingerprint, created_at,
               expires_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      ps.setString(1, id);
      ps.setString(2, label);
      ps.setString(3, hash);
      ps.setString(4, String.join(",", safeScopes));
      ps.setString(5, inboxId);
      ps.setString(6, String.join(",", safeChannels));
      ps.setString(7, credential.fingerprint());
      ps.setString(8, now.toString());
      ps.setString(9, expiresAt == null ? null : expiresAt.toString());
      ps.setString(10, createdBy);
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
    Instant now = Instant.now();
    lock.lock();
    try {
      ApiCredential credential = findByHashLocked(hash);
      if (credential == null || !credential.active(now)) {
        return Optional.empty();
      }
      if (credential.lastUsedAt() == null
          || credential.lastUsedAt().isBefore(now.minus(LAST_USED_THROTTLE))) {
        try (PreparedStatement ps =
            connection.prepareStatement(
                "UPDATE api_credential SET last_used_at = ? WHERE id = ?")) {
          ps.setString(1, now.toString());
          ps.setString(2, credential.id());
          ps.executeUpdate();
        } catch (SQLException e) {
          LOG.warn("更新 credential last_used_at 失败 id={}", credential.id(), e);
        }
      }
      return Optional.of(credential);
    } catch (SQLException e) {
      throw SqliteSupport.internal("认证 credential 失败", e);
    } finally {
      lock.unlock();
    }
  }

  public Optional<ApiCredential> findById(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    lock.lock();
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT * FROM api_credential WHERE id = ?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw SqliteSupport.internal("查询 credential 失败", e);
    } finally {
      lock.unlock();
    }
  }

  public List<ApiCredential> list(String inboxId) {
    lock.lock();
    try {
      String sql =
          inboxId == null || inboxId.isBlank()
              ? "SELECT * FROM api_credential ORDER BY created_at DESC"
              : "SELECT * FROM api_credential WHERE inbox_id = ? ORDER BY created_at DESC";
      try (PreparedStatement ps = connection.prepareStatement(sql)) {
        if (inboxId != null && !inboxId.isBlank()) {
          ps.setString(1, inboxId);
        }
        List<ApiCredential> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            out.add(map(rs));
          }
        }
        return List.copyOf(out);
      }
    } catch (SQLException e) {
      throw SqliteSupport.internal("列出 credential 失败", e);
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

  /**
   * 轮换：旧 credential 立即撤销，新 credential 继承 label/scopes/inbox/channels/expiry 与 created_by。
   * 返回的 raw token 只出现一次。
   */
  public IssuedCredential rotate(String credentialId, String rotatedBy) {
    lock.lock();
    try {
      connection.setAutoCommit(false);
      try {
        ApiCredential old = findByIdLocked(credentialId);
        if (old == null) {
          throw new InboxException(InboxException.Code.NOT_FOUND, "credential 不存在");
        }
        if (old.revokedAt() != null) {
          throw new InboxException(InboxException.Code.CONFLICT, "credential 已撤销");
        }
        String token = "inbox_" + randomToken();
        String hash = sha256(token);
        String id = "cred_" + UUID.randomUUID();
        Instant now = Instant.now();
        try (PreparedStatement ps =
            connection.prepareStatement(
                """
                INSERT INTO api_credential
                  (id, label, token_hash, scopes, inbox_id, channels, fingerprint, created_at,
                   expires_at, rotated_from, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
          ps.setString(1, id);
          ps.setString(2, old.label());
          ps.setString(3, hash);
          ps.setString(4, String.join(",", old.scopes()));
          ps.setString(5, old.inboxId());
          ps.setString(6, String.join(",", old.channels()));
          ps.setString(7, fingerprint(hash));
          ps.setString(8, now.toString());
          ps.setString(9, old.expiresAt() == null ? null : old.expiresAt().toString());
          ps.setString(10, old.id());
          ps.setString(11, rotatedBy == null ? old.createdBy() : rotatedBy);
          ps.executeUpdate();
        }
        try (PreparedStatement ps =
            connection.prepareStatement(
                "UPDATE api_credential SET revoked_at = ? WHERE id = ?")) {
          ps.setString(1, now.toString());
          ps.setString(2, old.id());
          ps.executeUpdate();
        }
        connection.commit();
        ApiCredential created =
            new ApiCredential(
                id,
                old.label(),
                old.scopes(),
                old.inboxId(),
                old.channels(),
                fingerprint(hash),
                now,
                old.expiresAt(),
                null,
                null,
                old.id(),
                rotatedBy == null ? old.createdBy() : rotatedBy);
        return new IssuedCredential(created, token);
      } catch (SQLException | RuntimeException e) {
        try {
          connection.rollback();
        } catch (SQLException rollbackError) {
          e.addSuppressed(rollbackError);
        }
        if (e instanceof RuntimeException runtime) {
          throw runtime;
        }
        throw SqliteSupport.internal("轮换 credential 失败", e);
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw SqliteSupport.internal("轮换 credential 事务失败", e);
    } finally {
      lock.unlock();
    }
  }

  private ApiCredential findByHashLocked(String hash) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT * FROM api_credential WHERE token_hash = ?")) {
      ps.setString(1, hash);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? map(rs) : null;
      }
    }
  }

  private ApiCredential findByIdLocked(String id) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT * FROM api_credential WHERE id = ?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? map(rs) : null;
      }
    }
  }

  private static ApiCredential map(ResultSet rs) throws SQLException {
    return new ApiCredential(
        rs.getString("id"),
        rs.getString("label"),
        parseSet(rs.getString("scopes")),
        rs.getString("inbox_id"),
        parseSet(rs.getString("channels")),
        rs.getString("fingerprint"),
        parseInstant(rs.getString("created_at")),
        parseInstant(rs.getString("expires_at")),
        parseInstant(rs.getString("last_used_at")),
        parseInstant(rs.getString("revoked_at")),
        rs.getString("rotated_from"),
        rs.getString("created_by"));
  }

  private static void validateIssue(
      String label, Set<String> scopes, String inboxId) {
    if (label == null || label.isBlank() || label.length() > 128) {
      throw SqliteSupport.invalid("label 必须为 1..128 字符");
    }
    if (scopes == null || scopes.isEmpty()) {
      throw SqliteSupport.invalid("至少需要一个 scope");
    }
    if (inboxId == null || inboxId.isBlank() || inboxId.length() > 64) {
      throw SqliteSupport.invalid("inboxId 必须为 1..64 字符");
    }
  }

  private static void ensureColumn(Statement statement, String name, String definition) {
    try {
      try (ResultSet rs =
          statement.executeQuery("PRAGMA table_info(api_credential)")) {
        while (rs.next()) {
          if (name.equalsIgnoreCase(rs.getString("name"))) {
            return;
          }
        }
      }
      statement.execute("ALTER TABLE api_credential ADD COLUMN " + name + " " + definition);
    } catch (SQLException e) {
      throw SqliteSupport.internal("迁移 api_credential." + name + " 失败", e);
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

  private static Instant parseInstant(String value) {
    return value == null || value.isBlank() ? null : Instant.parse(value);
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return B64.encodeToString(bytes);
  }

  private static String fingerprint(String hash) {
    return "sha256:" + hash.substring(0, 16);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of()
          .formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 缺少 SHA-256", e);
    }
  }
}

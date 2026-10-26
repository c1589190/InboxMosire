package io.mosire.inbox.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.inbox.core.AppendItemCommand;
import io.mosire.inbox.core.ChannelCounts;
import io.mosire.inbox.core.ClaimRejection;
import io.mosire.inbox.core.ClaimResult;
import io.mosire.inbox.core.ClaimedItem;
import io.mosire.inbox.core.CompleteResult;
import io.mosire.inbox.core.Completion;
import io.mosire.inbox.core.CompletionAck;
import io.mosire.inbox.core.CompletionOutcome;
import io.mosire.inbox.core.InboxOccupancy;
import io.mosire.inbox.core.InboxException;
import io.mosire.inbox.core.InboxStatus;
import io.mosire.inbox.core.InboxStore;
import io.mosire.inbox.core.ItemDetail;
import io.mosire.inbox.core.ItemMutationResult;
import io.mosire.inbox.core.ItemStatus;
import io.mosire.inbox.core.ItemSummary;
import io.mosire.inbox.core.PushCommand;
import io.mosire.inbox.core.PushResult;
import io.mosire.inbox.core.ReleaseResult;
import io.mosire.inbox.core.RenewResult;
import io.mosire.inbox.core.RenewedClaim;
import io.mosire.inbox.core.StoredEvent;
import io.mosire.inbox.core.UpdateItemCommand;
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
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** SQLite 实现。连接与锁由 {@link SqliteDatabase} 统一持有。 */
public final class SqliteInboxStore implements InboxStore {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, ChannelCounts>> CHANNELS_TYPE =
      new TypeReference<>() {};
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private final Connection connection;
  private final ReentrantLock lock;

  SqliteInboxStore(Connection connection, ReentrantLock lock) {
    this.connection = connection;
    this.lock = lock;
  }

  @Override
  public void initialize() {
    lock.lock();
    try (Statement st = connection.createStatement()) {
      exec(
          st,
          """
          CREATE TABLE IF NOT EXISTS schema_migration (
            version    INTEGER PRIMARY KEY,
            applied_at TEXT NOT NULL
          )
          """);
      exec(
          st,
          """
          CREATE TABLE IF NOT EXISTS inbox (
            id                  TEXT PRIMARY KEY,
            revision            INTEGER NOT NULL DEFAULT 0,
            created_at          TEXT NOT NULL,
            updated_at          TEXT NOT NULL,
            occupied            INTEGER NOT NULL DEFAULT 0,
            occupied_by         TEXT,
            occupied_at         TEXT,
            occupancy_updated_at TEXT
          )
          """);
      exec(
          st,
          """
          CREATE TABLE IF NOT EXISTS inbox_item (
            id              TEXT PRIMARY KEY,
            inbox_id        TEXT NOT NULL REFERENCES inbox(id) ON DELETE CASCADE,
            channel         TEXT NOT NULL,
            kind            TEXT NOT NULL,
            title           TEXT,
            payload         TEXT,
            payload_ref     TEXT,
            dedupe_key      TEXT,
            priority        INTEGER NOT NULL DEFAULT 0,
            status          TEXT NOT NULL,
            attempts        INTEGER NOT NULL DEFAULT 0,
            revision        INTEGER NOT NULL DEFAULT 0,
            created_at      TEXT NOT NULL,
            updated_at      TEXT NOT NULL,
            available_at    TEXT NOT NULL,
            claimed_at      TEXT,
            completed_at    TEXT,
            outcome         TEXT,
            outcome_summary TEXT
          )
          """);
      exec(
          st,
          "CREATE INDEX IF NOT EXISTS idx_item_inbox_status ON inbox_item(inbox_id, status, channel)");
      exec(
          st,
          """
          CREATE UNIQUE INDEX IF NOT EXISTS uq_item_active_dedupe
          ON inbox_item(inbox_id, channel, dedupe_key)
          WHERE dedupe_key IS NOT NULL AND status <> 'done'
          """);
      exec(
          st,
          """
          CREATE TABLE IF NOT EXISTS inbox_claim (
            id              TEXT PRIMARY KEY,
            item_id         TEXT NOT NULL UNIQUE REFERENCES inbox_item(id) ON DELETE CASCADE,
            inbox_id        TEXT NOT NULL REFERENCES inbox(id) ON DELETE CASCADE,
            token_hash      TEXT NOT NULL UNIQUE,
            claimed_at      TEXT NOT NULL,
            expires_at      TEXT NOT NULL,
            status          TEXT NOT NULL,
            completed_at    TEXT,
            outcome         TEXT,
            outcome_summary TEXT
          )
          """);
      exec(
          st,
          "CREATE INDEX IF NOT EXISTS idx_claim_expiry ON inbox_claim(status, expires_at)");
      exec(
          st,
          """
          CREATE TABLE IF NOT EXISTS inbox_event (
            seq               INTEGER PRIMARY KEY AUTOINCREMENT,
            event_id          TEXT NOT NULL UNIQUE,
            inbox_id          TEXT NOT NULL REFERENCES inbox(id) ON DELETE CASCADE,
            revision          INTEGER NOT NULL,
            available         INTEGER NOT NULL,
            claimed           INTEGER NOT NULL,
            channels_json     TEXT NOT NULL,
            changed_channels  TEXT NOT NULL,
            created_at        TEXT NOT NULL
          )
          """);
      exec(st, "CREATE INDEX IF NOT EXISTS idx_event_inbox_seq ON inbox_event(inbox_id, seq)");
      ensureColumn(st, "inbox", "occupied", "INTEGER NOT NULL DEFAULT 0");
      ensureColumn(st, "inbox", "occupied_by", "TEXT");
      ensureColumn(st, "inbox", "occupied_at", "TEXT");
      ensureColumn(st, "inbox", "occupancy_updated_at", "TEXT");
      exec(
          st,
          "INSERT OR IGNORE INTO schema_migration(version, applied_at) VALUES (1, '"
              + Instant.now()
              + "')");
      String now = Instant.now().toString();
      try (PreparedStatement ps =
          connection.prepareStatement(
              "INSERT OR IGNORE INTO inbox(id, revision, created_at, updated_at) VALUES ('main', 0, ?, ?)")) {
        ps.setString(1, now);
        ps.setString(2, now);
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw SqliteSupport.internal("初始化 inbox schema 失败", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean createInbox(String inboxId) {
    lock.lock();
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO inbox(id, revision, created_at, updated_at)
            VALUES (?, 0, ?, ?)
            ON CONFLICT(id) DO NOTHING
            """)) {
      String now = Instant.now().toString();
      ps.setString(1, inboxId);
      ps.setString(2, now);
      ps.setString(3, now);
      return ps.executeUpdate() == 1;
    } catch (SQLException e) {
      throw SqliteSupport.internal("创建 inbox 失败", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean inboxExists(String inboxId) {
    lock.lock();
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT 1 FROM inbox WHERE id = ?")) {
      ps.setString(1, inboxId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      throw SqliteSupport.internal("查询 inbox 失败", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public PushResult push(PushCommand command, Instant now) {
    return inTransaction(
        () -> {
          requireInboxLocked(command.inboxId());
          if (command.dedupeKey() != null) {
            try (PreparedStatement ps =
                connection.prepareStatement(
                    """
                    SELECT id, created_at FROM inbox_item
                    WHERE inbox_id = ? AND channel = ? AND dedupe_key = ? AND status <> 'done'
                    LIMIT 1
                    """)) {
              ps.setString(1, command.inboxId());
              ps.setString(2, command.channel());
              ps.setString(3, command.dedupeKey());
              try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                  long revision = currentRevisionLocked(command.inboxId());
                  return new PushResult(
                      rs.getString("id"), true, revision, Instant.parse(rs.getString("created_at")));
                }
              }
            }
          }

          long revision = nextRevisionLocked(command.inboxId(), now);
          String itemId = "itm_" + UUID.randomUUID();
          try (PreparedStatement ps =
              connection.prepareStatement(
                  """
                  INSERT INTO inbox_item
                    (id, inbox_id, channel, kind, title, payload, payload_ref, dedupe_key,
                     priority, status, attempts, revision, created_at, updated_at, available_at)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'available', 0, ?, ?, ?, ?)
                  """)) {
            ps.setString(1, itemId);
            ps.setString(2, command.inboxId());
            ps.setString(3, command.channel());
            ps.setString(4, command.kind());
            ps.setString(5, command.summary());
            ps.setString(6, command.content());
            ps.setString(7, command.contentRef());
            ps.setString(8, command.dedupeKey());
            ps.setInt(9, command.priority());
            ps.setLong(10, revision);
            String created = now.toString();
            ps.setString(11, created);
            ps.setString(12, created);
            Instant availableAt = command.availableAt() == null ? now : command.availableAt();
            ps.setString(13, availableAt.toString());
            ps.executeUpdate();
          }
          recordEventLocked(
              command.inboxId(), revision, List.of(command.channel()), now);
          return new PushResult(itemId, false, revision, now);
        });
  }

  @Override
  public InboxOccupancy occupancy(String inboxId) {
    lock.lock();
    try {
      return occupancyLocked(inboxId);
    } catch (SQLException e) {
      throw SqliteSupport.internal("查询 inbox 占用状态失败", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public InboxOccupancy occupy(String inboxId, String occupiedBy, Instant now) {
    lock.lock();
    try {
      InboxOccupancy current = occupancyLocked(inboxId);
      if (!current.occupied()) {
        try (PreparedStatement ps =
            connection.prepareStatement(
                """
                UPDATE inbox
                SET occupied = 1, occupied_by = ?, occupied_at = ?, occupancy_updated_at = ?
                WHERE id = ?
                """)) {
          ps.setString(1, occupiedBy);
          ps.setString(2, now.toString());
          ps.setString(3, now.toString());
          ps.setString(4, inboxId);
          ps.executeUpdate();
        }
      }
      return occupancyLocked(inboxId);
    } catch (SQLException e) {
      throw SqliteSupport.internal("标记 inbox 占用失败", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public InboxOccupancy vacate(String inboxId, Instant now) {
    lock.lock();
    try {
      try (PreparedStatement ps =
          connection.prepareStatement(
              """
              UPDATE inbox
              SET occupied = 0, occupied_by = NULL, occupied_at = NULL, occupancy_updated_at = ?
              WHERE id = ?
              """)) {
        ps.setString(1, now.toString());
        ps.setString(2, inboxId);
        if (ps.executeUpdate() != 1) {
          throw SqliteSupport.notFound("inbox 不存在: " + inboxId);
        }
      }
      return occupancyLocked(inboxId);
    } catch (SQLException e) {
      throw SqliteSupport.internal("释放 inbox 占用失败", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void clearAllOccupancy() {
    lock.lock();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "UPDATE inbox SET occupied = 0, occupied_by = NULL, occupied_at = NULL, "
                + "occupancy_updated_at = ?")) {
      ps.setString(1, Instant.now().toString());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw SqliteSupport.internal("清理 inbox 占用状态失败", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public InboxStatus status(String inboxId, Set<String> channels) {
    lock.lock();
    try {
      return statusLocked(inboxId, channels);
    } catch (SQLException e) {
      throw SqliteSupport.internal("查询 inbox status 失败", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public List<ItemSummary> listAvailable(
      String inboxId, Set<String> channels, int limit, Instant now) {
    lock.lock();
    try {
      requireInboxLocked(inboxId);
      String sql =
          """
          SELECT id, channel, kind, title, priority, attempts, created_at
          FROM inbox_item
          WHERE inbox_id = ? AND status = 'available' AND available_at <= ?
          """
              + channelPredicate(channels)
              + " ORDER BY priority DESC, created_at ASC LIMIT ?";
      try (PreparedStatement ps = connection.prepareStatement(sql)) {
        int index = 1;
        ps.setString(index++, inboxId);
        ps.setString(index++, now.toString());
        index = bindChannels(ps, index, channels);
        ps.setInt(index, limit);
        List<ItemSummary> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            out.add(
                new ItemSummary(
                    rs.getString("id"),
                    rs.getString("channel"),
                    rs.getString("kind"),
                    rs.getString("title"),
                    rs.getInt("priority"),
                    rs.getInt("attempts"),
                    Instant.parse(rs.getString("created_at"))));
          }
        }
        return List.copyOf(out);
      }
    } catch (SQLException e) {
      throw SqliteSupport.internal("列出 available item 失败", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public ItemDetail viewItem(String inboxId, String itemId, Set<String> allowedChannels) {
    lock.lock();
    try {
      ItemRow row = findItemLocked(inboxId, itemId);
      if (row.status() == ItemStatus.DONE) {
        throw SqliteSupport.notFound("通知已归档: " + itemId);
      }
      requireAllowedChannel(row.channel(), allowedChannels);
      return row.detail();
    } catch (SQLException e) {
      throw SqliteSupport.internal("查看 item 失败", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public ItemMutationResult updateItem(
      UpdateItemCommand command, Instant now, Set<String> allowedChannels) {
    return mutateAvailableItem(
        command.inboxId(),
        command.itemId(),
        command.expectedRevision(),
        allowedChannels,
        now,
        row -> new ItemUpdate(
            command.summary() == null ? row.summary() : command.summary(),
            command.content() == null ? row.content() : command.content(),
            command.contentRef() == null ? row.contentRef() : command.contentRef(),
            command.kind() == null ? row.kind() : command.kind(),
            command.priority() == null ? row.priority() : command.priority()));
  }

  @Override
  public ItemMutationResult appendItem(
      AppendItemCommand command, Instant now, Set<String> allowedChannels) {
    return mutateAvailableItem(
        command.inboxId(),
        command.itemId(),
        command.expectedRevision(),
        allowedChannels,
        now,
        row -> {
          String appended =
              row.content() == null || row.content().isBlank()
                  ? command.content()
                  : row.content() + "\n" + command.content();
          return new ItemUpdate(
              command.summary() == null ? row.summary() : command.summary(),
              appended,
              row.contentRef(),
              row.kind(),
              command.priority() == null ? row.priority() : command.priority());
        });
  }

  @Override
  public ItemMutationResult deleteItem(
      String inboxId,
      String itemId,
      Long expectedRevision,
      Instant now,
      Set<String> allowedChannels) {
    return inTransaction(
        () -> {
          ItemRow row = findItemLocked(inboxId, itemId);
          requireAllowedChannel(row.channel(), allowedChannels);
          requireAvailable(row);
          requireExpectedRevision(row, expectedRevision);
          long revision = nextRevisionLocked(inboxId, now);
          try (PreparedStatement ps =
              connection.prepareStatement(
                  """
                  UPDATE inbox_item
                  SET status = 'done', revision = ?, updated_at = ?, completed_at = ?,
                      outcome = 'deleted', outcome_summary = 'deleted by inbox_delete'
                  WHERE id = ?
                  """)) {
            ps.setLong(1, revision);
            ps.setString(2, now.toString());
            ps.setString(3, now.toString());
            ps.setString(4, itemId);
            ps.executeUpdate();
          }
          recordEventLocked(inboxId, revision, List.of(row.channel()), now);
          InboxStatus status = statusLocked(inboxId, Set.of());
          return new ItemMutationResult(
              itemId, status.revision(), status.available(), now, true);
        });
  }

  private ItemMutationResult mutateAvailableItem(
      String inboxId,
      String itemId,
      Long expectedRevision,
      Set<String> allowedChannels,
      Instant now,
      ItemUpdateFunction updater) {
    return inTransaction(
        () -> {
          ItemRow row = findItemLocked(inboxId, itemId);
          requireAllowedChannel(row.channel(), allowedChannels);
          requireAvailable(row);
          requireExpectedRevision(row, expectedRevision);
          ItemUpdate update = updater.apply(row);
          long revision = nextRevisionLocked(inboxId, now);
          try (PreparedStatement ps =
              connection.prepareStatement(
                  """
                  UPDATE inbox_item
                  SET title = ?, payload = ?, payload_ref = ?, kind = ?, priority = ?,
                      revision = ?, updated_at = ?
                  WHERE id = ? AND status = 'available'
                  """)) {
            ps.setString(1, update.summary());
            ps.setString(2, update.content());
            ps.setString(3, update.contentRef());
            ps.setString(4, update.kind());
            ps.setInt(5, update.priority());
            ps.setLong(6, revision);
            ps.setString(7, now.toString());
            ps.setString(8, itemId);
            ps.executeUpdate();
          }
          recordEventLocked(inboxId, revision, List.of(row.channel()), now);
          InboxStatus status = statusLocked(inboxId, Set.of());
          return new ItemMutationResult(
              itemId, status.revision(), status.available(), now, false);
        });
  }

  private ItemRow findItemLocked(String inboxId, String itemId) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            SELECT id, inbox_id, channel, kind, title, payload, payload_ref, priority,
                   status, attempts, revision, created_at, updated_at
            FROM inbox_item
            WHERE id = ? AND inbox_id = ?
            """)) {
      ps.setString(1, itemId);
      ps.setString(2, inboxId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw SqliteSupport.notFound("通知不存在或已归档: " + itemId);
        }
        return new ItemRow(
            rs.getString("id"),
            rs.getString("inbox_id"),
            rs.getString("channel"),
            rs.getString("kind"),
            rs.getString("title"),
            rs.getString("payload"),
            rs.getString("payload_ref"),
            rs.getInt("priority"),
            ItemStatus.fromWire(rs.getString("status")),
            rs.getInt("attempts"),
            rs.getLong("revision"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at")));
      }
    }
  }

  private static void requireAllowedChannel(String channel, Set<String> allowedChannels) {
    if (!allowedChannels.isEmpty() && !allowedChannels.contains(channel)) {
      throw SqliteSupport.notFound("通知不在 token 的 channel 范围内");
    }
  }

  private static void requireAvailable(ItemRow row) {
    if (row.status() != ItemStatus.AVAILABLE) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("itemStatus", row.status().wireValue());
      details.put("allowedActions", allowedActions(row.status()));
      throw new InboxException(
          InboxException.Code.ITEM_STATUS_CONFLICT,
          "item 当前状态不允许该操作: " + row.status().wireValue(),
          details);
    }
  }

  private static List<String> allowedActions(ItemStatus status) {
    return switch (status) {
      case CLAIMED -> List.of("complete", "release");
      case DONE -> List.of();
      case AVAILABLE -> List.of("update", "append", "delete");
    };
  }

  private static void requireExpectedRevision(ItemRow row, Long expectedRevision) {
    if (expectedRevision != null && expectedRevision != row.revision()) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("expectedRevision", expectedRevision);
      details.put("actualRevision", row.revision());
      details.put("itemStatus", row.status().wireValue());
      throw new InboxException(
          InboxException.Code.ITEM_REVISION_CONFLICT,
          "item revision 不匹配: expected=" + expectedRevision + " actual=" + row.revision(),
          details);
    }
  }

  @Override
  public ClaimResult claim(String inboxId, List<String> itemIds, Duration lease, Instant now) {
    return claim(inboxId, itemIds, lease, now, Set.of());
  }

  @Override
  public ClaimResult claim(
      String inboxId,
      List<String> itemIds,
      Duration lease,
      Instant now,
      Set<String> allowedChannels) {
    return inTransaction(
        () -> {
          long revision = requireInboxRevisionLocked(inboxId);
          List<ClaimedItem> claimed = new ArrayList<>();
          List<ClaimRejection> rejected = new ArrayList<>();
          Set<String> changedChannels = new LinkedHashSet<>();

          for (String itemId : itemIds) {
            try (PreparedStatement ps =
                connection.prepareStatement(
                    """
                    SELECT id, channel, kind, title, payload, payload_ref, priority, status,
                           available_at
                    FROM inbox_item WHERE id = ? AND inbox_id = ?
                    """)) {
              ps.setString(1, itemId);
              ps.setString(2, inboxId);
              try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                  rejected.add(new ClaimRejection(itemId, "ITEM_NOT_FOUND", "通知不存在"));
                  continue;
                }
                if (!allowedChannels.isEmpty() && !allowedChannels.contains(rs.getString("channel"))) {
                  rejected.add(new ClaimRejection(itemId, "ITEM_UNAVAILABLE", "通知不在 token 的 channel 范围内"));
                  continue;
                }
                if (!"available".equals(rs.getString("status"))) {
                  rejected.add(new ClaimRejection(itemId, "ITEM_UNAVAILABLE", "通知已被领取或完成"));
                  continue;
                }
                if (Instant.parse(rs.getString("available_at")).isAfter(now)) {
                  rejected.add(new ClaimRejection(itemId, "ITEM_NOT_READY", "通知尚未到可处理时间"));
                  continue;
                }
                if (claimed.isEmpty()) {
                  revision = nextRevisionLocked(inboxId, now);
                }
                String claimToken = "clm_" + randomToken();
                String claimHash = sha256(claimToken);
                Instant expiresAt = now.plus(lease);
                try (PreparedStatement update =
                    connection.prepareStatement(
                        """
                        UPDATE inbox_item
                        SET status = 'claimed', revision = ?, updated_at = ?, claimed_at = ?
                        WHERE id = ? AND status = 'available'
                        """)) {
                  update.setLong(1, revision);
                  update.setString(2, now.toString());
                  update.setString(3, now.toString());
                  update.setString(4, itemId);
                  if (update.executeUpdate() != 1) {
                    rejected.add(new ClaimRejection(itemId, "ITEM_UNAVAILABLE", "通知竞争失败"));
                    continue;
                  }
                }
                try (PreparedStatement insert =
                    connection.prepareStatement(
                        """
                        INSERT INTO inbox_claim
                          (id, item_id, inbox_id, token_hash, claimed_at, expires_at, status)
                        VALUES (?, ?, ?, ?, ?, ?, 'active')
                        """)) {
                  insert.setString(1, "claim_" + UUID.randomUUID());
                  insert.setString(2, itemId);
                  insert.setString(3, inboxId);
                  insert.setString(4, claimHash);
                  insert.setString(5, now.toString());
                  insert.setString(6, expiresAt.toString());
                  insert.executeUpdate();
                }
                claimed.add(
                    new ClaimedItem(
                        itemId,
                        rs.getString("channel"),
                        rs.getString("kind"),
                        rs.getString("title"),
                        rs.getString("payload"),
                        rs.getString("payload_ref"),
                        rs.getInt("priority"),
                        claimToken,
                        expiresAt));
                changedChannels.add(rs.getString("channel"));
              }
            }
          }

          if (!claimed.isEmpty()) {
            recordEventLocked(inboxId, revision, List.copyOf(changedChannels), now);
          }
          InboxStatus status = statusLocked(inboxId, Set.of());
          return new ClaimResult(
              status.revision(), status.available(), List.copyOf(claimed), List.copyOf(rejected));
        });
  }

  @Override
  public RenewResult renew(
      String inboxId, List<String> claimTokens, Duration lease, Instant now) {
    return inTransaction(
        () -> {
          requireInboxLocked(inboxId);
          List<RenewedClaim> renewed = new ArrayList<>();
          List<ClaimRejection> rejected = new ArrayList<>();
          for (String token : claimTokens) {
            ClaimRow row = findClaimLocked(inboxId, token);
            if (row == null) {
              rejected.add(new ClaimRejection(token, "CLAIM_INVALID", "claim 不存在"));
              continue;
            }
            if (!"active".equals(row.status())) {
              rejected.add(new ClaimRejection(token, "CLAIM_COMPLETED", "claim 已完成"));
              continue;
            }
            if (row.expiresAt().isBefore(now)) {
              rejected.add(new ClaimRejection(token, "CLAIM_EXPIRED", "claim 已过期"));
              continue;
            }
            Instant expiresAt = now.plus(lease);
            try (PreparedStatement ps =
                connection.prepareStatement(
                    "UPDATE inbox_claim SET expires_at = ? WHERE id = ? AND status = 'active'")) {
              ps.setString(1, expiresAt.toString());
              ps.setString(2, row.id());
              ps.executeUpdate();
            }
            renewed.add(new RenewedClaim(token, row.itemId(), expiresAt));
          }
          return new RenewResult(List.copyOf(renewed), List.copyOf(rejected));
        });
  }

  @Override
  public CompleteResult complete(
      String inboxId, List<Completion> completions, Instant now) {
    return inTransaction(
        () -> {
          long revision = requireInboxRevisionLocked(inboxId);
          List<CompletionAck> completed = new ArrayList<>();
          List<ClaimRejection> rejected = new ArrayList<>();
          Set<String> changedChannels = new LinkedHashSet<>();

          for (Completion completion : completions) {
            ClaimRow row = findClaimLocked(inboxId, completion.claimToken());
            if (row == null) {
              rejected.add(
                  new ClaimRejection(completion.claimToken(), "CLAIM_INVALID", "claim 不存在"));
              continue;
            }
            if ("completed".equals(row.status())) {
              CompletionOutcome outcome =
                  row.outcome() == null
                      ? CompletionOutcome.COMPLETED
                      : CompletionOutcome.fromWire(row.outcome());
              completed.add(new CompletionAck(row.itemId(), outcome, true));
              continue;
            }
            if (!"active".equals(row.status())) {
              rejected.add(
                  new ClaimRejection(completion.claimToken(), "CLAIM_INVALID", "claim 状态不可完成"));
              continue;
            }
            if (row.expiresAt().isBefore(now)) {
              rejected.add(
                  new ClaimRejection(completion.claimToken(), "CLAIM_EXPIRED", "claim 已过期"));
              continue;
            }
            if (completed.stream().noneMatch(ack -> !ack.idempotent())) {
              revision = nextRevisionLocked(inboxId, now);
            }
            try (PreparedStatement ps =
                connection.prepareStatement(
                    """
                    UPDATE inbox_claim
                    SET status = 'completed', completed_at = ?, outcome = ?, outcome_summary = ?
                    WHERE id = ? AND status = 'active'
                    """)) {
              ps.setString(1, now.toString());
              ps.setString(2, completion.outcome().wireValue());
              ps.setString(3, completion.summary());
              ps.setString(4, row.id());
              if (ps.executeUpdate() != 1) {
                rejected.add(
                    new ClaimRejection(
                        completion.claimToken(), "CLAIM_CONFLICT", "claim 状态竞争失败"));
                continue;
              }
            }
            try (PreparedStatement ps =
                connection.prepareStatement(
                    """
                    UPDATE inbox_item
                    SET status = 'done', revision = ?, updated_at = ?, completed_at = ?,
                        outcome = ?, outcome_summary = ?
                    WHERE id = ?
                    """)) {
              ps.setLong(1, revision);
              ps.setString(2, now.toString());
              ps.setString(3, now.toString());
              ps.setString(4, completion.outcome().wireValue());
              ps.setString(5, completion.summary());
              ps.setString(6, row.itemId());
              ps.executeUpdate();
            }
            completed.add(new CompletionAck(row.itemId(), completion.outcome(), false));
            changedChannels.add(row.channel());
          }

          boolean changed = completed.stream().anyMatch(ack -> !ack.idempotent());
          if (changed) {
            recordEventLocked(inboxId, revision, List.copyOf(changedChannels), now);
          }
          InboxStatus status = statusLocked(inboxId, Set.of());
          return new CompleteResult(
              status.revision(), status.available(), List.copyOf(completed), List.copyOf(rejected));
        });
  }

  @Override
  public ReleaseResult release(String inboxId, List<String> claimTokens, Instant now) {
    return inTransaction(
        () -> {
          long revision = requireInboxRevisionLocked(inboxId);
          List<String> released = new ArrayList<>();
          List<ClaimRejection> rejected = new ArrayList<>();
          Set<String> changedChannels = new LinkedHashSet<>();

          for (String token : claimTokens) {
            ClaimRow row = findClaimLocked(inboxId, token);
            if (row == null) {
              rejected.add(new ClaimRejection(token, "CLAIM_INVALID", "claim 不存在"));
              continue;
            }
            if (!"active".equals(row.status())) {
              rejected.add(new ClaimRejection(token, "CLAIM_COMPLETED", "claim 已完成"));
              continue;
            }
            if (row.expiresAt().isBefore(now)) {
              rejected.add(new ClaimRejection(token, "CLAIM_EXPIRED", "claim 已过期"));
              continue;
            }
            if (released.isEmpty()) {
              revision = nextRevisionLocked(inboxId, now);
            }
            try (PreparedStatement ps =
                connection.prepareStatement(
                    """
                    UPDATE inbox_item
                    SET status = 'available', attempts = attempts + 1, revision = ?,
                        updated_at = ?, claimed_at = NULL
                    WHERE id = ?
                    """)) {
              ps.setLong(1, revision);
              ps.setString(2, now.toString());
              ps.setString(3, row.itemId());
              ps.executeUpdate();
            }
            try (PreparedStatement ps =
                connection.prepareStatement("DELETE FROM inbox_claim WHERE id = ?")) {
              ps.setString(1, row.id());
              ps.executeUpdate();
            }
            released.add(row.itemId());
            changedChannels.add(row.channel());
          }

          if (!released.isEmpty()) {
            recordEventLocked(inboxId, revision, List.copyOf(changedChannels), now);
          }
          InboxStatus status = statusLocked(inboxId, Set.of());
          return new ReleaseResult(
              status.revision(), status.available(), List.copyOf(released), List.copyOf(rejected));
        });
  }

  @Override
  public int expireClaims(Instant now, int limit) {
    return inTransaction(
        () -> {
          List<ExpiredRow> expired = new ArrayList<>();
          try (PreparedStatement ps =
              connection.prepareStatement(
                  """
                  SELECT c.id, c.item_id, c.inbox_id, i.channel
                  FROM inbox_claim c
                  JOIN inbox_item i ON i.id = c.item_id
                  WHERE c.status = 'active' AND c.expires_at <= ?
                  ORDER BY c.expires_at ASC
                  LIMIT ?
                  """)) {
            ps.setString(1, now.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                expired.add(
                    new ExpiredRow(
                        rs.getString("id"),
                        rs.getString("item_id"),
                        rs.getString("inbox_id"),
                        rs.getString("channel")));
              }
            }
          }
          if (expired.isEmpty()) {
            return 0;
          }

          Map<String, Set<String>> byInbox = new LinkedHashMap<>();
          Map<String, List<ExpiredRow>> rowsByInbox = new LinkedHashMap<>();
          for (ExpiredRow row : expired) {
            byInbox.computeIfAbsent(row.inboxId(), ignored -> new LinkedHashSet<>()).add(row.channel());
            rowsByInbox.computeIfAbsent(row.inboxId(), ignored -> new ArrayList<>()).add(row);
          }

          for (Map.Entry<String, List<ExpiredRow>> entry : rowsByInbox.entrySet()) {
            String inboxId = entry.getKey();
            long revision = nextRevisionLocked(inboxId, now);
            for (ExpiredRow row : entry.getValue()) {
              try (PreparedStatement ps =
                  connection.prepareStatement(
                      """
                      UPDATE inbox_item
                      SET status = 'available', attempts = attempts + 1, revision = ?,
                          updated_at = ?, claimed_at = NULL
                      WHERE id = ?
                      """)) {
                ps.setLong(1, revision);
                ps.setString(2, now.toString());
                ps.setString(3, row.itemId());
                ps.executeUpdate();
              }
              try (PreparedStatement ps =
                  connection.prepareStatement("DELETE FROM inbox_claim WHERE id = ?")) {
                ps.setString(1, row.claimId());
                ps.executeUpdate();
              }
            }
            recordEventLocked(inboxId, revision, List.copyOf(byInbox.get(inboxId)), now);
          }
          return expired.size();
        });
  }

  @Override
  public List<StoredEvent> eventsAfter(String inboxId, long afterSeq, int limit) {
    lock.lock();
    try {
      requireInboxLocked(inboxId);
      try (PreparedStatement ps =
          connection.prepareStatement(
              """
              SELECT seq, event_id, inbox_id, revision, available, claimed,
                     channels_json, changed_channels, created_at
              FROM inbox_event
              WHERE inbox_id = ? AND seq > ?
              ORDER BY seq ASC
              LIMIT ?
              """)) {
        ps.setString(1, inboxId);
        ps.setLong(2, afterSeq);
        ps.setInt(3, limit);
        try (ResultSet rs = ps.executeQuery()) {
          List<StoredEvent> out = new ArrayList<>();
          while (rs.next()) {
            Map<String, ChannelCounts> channels = parseChannels(rs.getString("channels_json"));
            List<String> changed = splitCsv(rs.getString("changed_channels"));
            out.add(
                new StoredEvent(
                    rs.getLong("seq"),
                    rs.getString("event_id"),
                    rs.getString("inbox_id"),
                    rs.getLong("revision"),
                    rs.getLong("available"),
                    rs.getLong("claimed"),
                    channels,
                    changed,
                    Instant.parse(rs.getString("created_at"))));
          }
          return List.copyOf(out);
        }
      }
    } catch (SQLException e) {
      throw SqliteSupport.internal("查询 event 失败", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public long latestEventSeq(String inboxId) {
    lock.lock();
    try {
      requireInboxLocked(inboxId);
      try (PreparedStatement ps =
          connection.prepareStatement(
              "SELECT COALESCE(MAX(seq), 0) AS latest FROM inbox_event WHERE inbox_id = ?")) {
        ps.setString(1, inboxId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getLong("latest") : 0L;
        }
      }
    } catch (SQLException e) {
      throw SqliteSupport.internal("查询最新 event seq 失败", e);
    } finally {
      lock.unlock();
    }
  }

  /** 共享连接归 SqliteDatabase 所有，store 本身不单独 close。 */
  @Override
  public void close() {
    // no-op
  }

  private InboxStatus recordEventLocked(
      String inboxId, long revision, List<String> changedChannels, Instant now) throws SQLException {
    InboxStatus status = statusLocked(inboxId, Set.of());
    String channelsJson = writeChannels(status.channels());
    String changed = String.join(",", new LinkedHashSet<>(changedChannels));
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO inbox_event
              (event_id, inbox_id, revision, available, claimed, channels_json,
               changed_channels, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      ps.setString(1, "evt_" + UUID.randomUUID());
      ps.setString(2, inboxId);
      ps.setLong(3, revision);
      ps.setLong(4, status.available());
      ps.setLong(5, status.claimed());
      ps.setString(6, channelsJson);
      ps.setString(7, changed);
      ps.setString(8, now.toString());
      ps.executeUpdate();
    }
    return status;
  }

  private InboxStatus statusLocked(String inboxId, Set<String> channels) throws SQLException {
    long revision;
    Instant updatedAt;
    InboxOccupancy occupancy;
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT revision, updated_at, occupied, occupied_by, occupied_at, occupancy_updated_at "
                + "FROM inbox WHERE id = ?")) {
      ps.setString(1, inboxId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw SqliteSupport.notFound("inbox 不存在: " + inboxId);
        }
        revision = rs.getLong("revision");
        updatedAt = Instant.parse(rs.getString("updated_at"));
        occupancy =
            new InboxOccupancy(
                inboxId,
                rs.getInt("occupied") != 0,
                rs.getString("occupied_by"),
                parseInstant(rs.getString("occupied_at")),
                parseInstant(rs.getString("occupancy_updated_at")));
      }
    }

    Map<String, long[]> counts = new TreeMap<>();
    String sql =
        """
        SELECT channel, status, COUNT(*) AS amount
        FROM inbox_item
        WHERE inbox_id = ? AND status IN ('available', 'claimed')
        """
            + channelPredicate(channels)
            + " GROUP BY channel, status";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      int index = 1;
      ps.setString(index++, inboxId);
      index = bindChannels(ps, index, channels);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          long[] pair = counts.computeIfAbsent(rs.getString("channel"), ignored -> new long[2]);
          if ("available".equals(rs.getString("status"))) {
            pair[0] = rs.getLong("amount");
          } else {
            pair[1] = rs.getLong("amount");
          }
        }
      }
    }
    Map<String, ChannelCounts> channelCounts = new TreeMap<>();
    long available = 0;
    long claimed = 0;
    for (Map.Entry<String, long[]> entry : counts.entrySet()) {
      long a = entry.getValue()[0];
      long c = entry.getValue()[1];
      available += a;
      claimed += c;
      channelCounts.put(entry.getKey(), new ChannelCounts(a, c));
    }
    return new InboxStatus(
        inboxId,
        revision,
        available,
        claimed,
        Collections.unmodifiableMap(channelCounts),
        updatedAt,
        occupancy);
  }

  private InboxOccupancy occupancyLocked(String inboxId) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT occupied, occupied_by, occupied_at, occupancy_updated_at "
                + "FROM inbox WHERE id = ?")) {
      ps.setString(1, inboxId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw SqliteSupport.notFound("inbox 不存在: " + inboxId);
        }
        return new InboxOccupancy(
            inboxId,
            rs.getInt("occupied") != 0,
            rs.getString("occupied_by"),
            parseInstant(rs.getString("occupied_at")),
            parseInstant(rs.getString("occupancy_updated_at")));
      }
    }
  }

  private void requireInboxLocked(String inboxId) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM inbox WHERE id = ?")) {
      ps.setString(1, inboxId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw SqliteSupport.notFound("inbox 不存在: " + inboxId);
        }
      }
    }
  }

  private long requireInboxRevisionLocked(String inboxId) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT revision FROM inbox WHERE id = ?")) {
      ps.setString(1, inboxId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw SqliteSupport.notFound("inbox 不存在: " + inboxId);
        }
        return rs.getLong("revision");
      }
    }
  }

  private long currentRevisionLocked(String inboxId) throws SQLException {
    return requireInboxRevisionLocked(inboxId);
  }

  private long nextRevisionLocked(String inboxId, Instant now) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "UPDATE inbox SET revision = revision + 1, updated_at = ? WHERE id = ?")) {
      ps.setString(1, now.toString());
      ps.setString(2, inboxId);
      if (ps.executeUpdate() != 1) {
        throw SqliteSupport.notFound("inbox 不存在: " + inboxId);
      }
    }
    return requireInboxRevisionLocked(inboxId);
  }

  private ClaimRow findClaimLocked(String inboxId, String token) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            SELECT c.id, c.item_id, c.expires_at, c.status, c.outcome, i.channel
            FROM inbox_claim c
            JOIN inbox_item i ON i.id = c.item_id
            WHERE c.inbox_id = ? AND c.token_hash = ?
            """)) {
      ps.setString(1, inboxId);
      ps.setString(2, sha256(token));
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return new ClaimRow(
            rs.getString("id"),
            rs.getString("item_id"),
            rs.getString("channel"),
            Instant.parse(rs.getString("expires_at")),
            rs.getString("status"),
            rs.getString("outcome"));
      }
    }
  }

  private <T> T inTransaction(SqlWork<T> work) {
    lock.lock();
    try {
      connection.setAutoCommit(false);
      try {
        T result = work.run();
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        try {
          connection.rollback();
        } catch (SQLException rollbackError) {
          e.addSuppressed(rollbackError);
        }
        if (e instanceof RuntimeException runtime) {
          throw runtime;
        }
        throw SqliteSupport.internal("SQLite 事务失败", e);
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw SqliteSupport.internal("SQLite 事务状态失败", e);
    } finally {
      lock.unlock();
    }
  }

  private static String channelPredicate(Set<String> channels) {
    if (channels == null || channels.isEmpty()) {
      return "";
    }
    return " AND channel IN (" + String.join(",", Collections.nCopies(channels.size(), "?")) + ")";
  }

  private static int bindChannels(PreparedStatement ps, int index, Set<String> channels)
      throws SQLException {
    if (channels == null) {
      return index;
    }
    for (String channel : channels) {
      ps.setString(index++, channel);
    }
    return index;
  }

  private static String writeChannels(Map<String, ChannelCounts> channels) {
    try {
      return JSON.writeValueAsString(channels);
    } catch (JsonProcessingException e) {
      throw SqliteSupport.internal("序列化 channel counts 失败", e);
    }
  }

  private static Map<String, ChannelCounts> parseChannels(String value) {
    try {
      return JSON.readValue(value, CHANNELS_TYPE);
    } catch (JsonProcessingException e) {
      throw SqliteSupport.internal("解析 channel counts 失败", e);
    }
  }

  private static List<String> splitCsv(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return List.of(value.split(","));
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return B64.encodeToString(bytes);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of()
          .formatHex(digest.digest(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 缺少 SHA-256", e);
    }
  }

  private static void ensureColumn(
      Statement statement, String table, String name, String definition) {
    try {
      try (ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
        while (rs.next()) {
          if (name.equalsIgnoreCase(rs.getString("name"))) {
            return;
          }
        }
      }
      statement.execute("ALTER TABLE " + table + " ADD COLUMN " + name + " " + definition);
    } catch (SQLException e) {
      throw SqliteSupport.internal("迁移 " + table + "." + name + " 失败", e);
    }
  }

  private static Instant parseInstant(String value) {
    return value == null || value.isBlank() ? null : Instant.parse(value);
  }

  private static void exec(Statement statement, String sql) throws SQLException {
    statement.execute(sql);
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }

  private record ItemUpdate(
      String summary, String content, String contentRef, String kind, int priority) {}

  private record ItemRow(
      String itemId,
      String inboxId,
      String channel,
      String kind,
      String summary,
      String content,
      String contentRef,
      int priority,
      ItemStatus status,
      int attempts,
      long revision,
      Instant createdAt,
      Instant updatedAt) {
    ItemDetail detail() {
      return new ItemDetail(
          itemId,
          inboxId,
          channel,
          kind,
          summary,
          content,
          contentRef,
          priority,
          status,
          attempts,
          revision,
          createdAt,
          updatedAt);
    }
  }

  @FunctionalInterface
  private interface ItemUpdateFunction {
    ItemUpdate apply(ItemRow row);
  }

  private record ClaimRow(
      String id, String itemId, String channel, Instant expiresAt, String status, String outcome) {}

  private record ExpiredRow(String claimId, String itemId, String inboxId, String channel) {}
}

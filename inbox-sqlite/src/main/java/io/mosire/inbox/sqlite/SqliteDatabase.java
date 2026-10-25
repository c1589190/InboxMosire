package io.mosire.inbox.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** 单 SQLite 连接 + 单写锁；InboxStore 和 CredentialStore 共享同一事务边界。 */
public final class SqliteDatabase implements AutoCloseable {
  private final Connection connection;
  private final ReentrantLock lock;
  private final SqliteInboxStore inboxStore;
  private final SqliteApiCredentialStore credentialStore;

  private SqliteDatabase(Connection connection) {
    this.connection = connection;
    this.lock = new ReentrantLock();
    this.inboxStore = new SqliteInboxStore(connection, lock);
    this.credentialStore = new SqliteApiCredentialStore(connection, lock);
  }

  public static SqliteDatabase open(Path dbFile) {
    Objects.requireNonNull(dbFile, "dbFile");
    try {
      Path parent = dbFile.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
      configure(connection);
      SqliteDatabase database = new SqliteDatabase(connection);
      database.inboxStore.initialize();
      database.credentialStore.initialize();
      return database;
    } catch (IOException e) {
      throw new IllegalStateException("无法创建数据库目录: " + dbFile, e);
    } catch (SQLException e) {
      throw new IllegalStateException("无法打开 SQLite: " + dbFile, e);
    }
  }

  private static void configure(Connection connection) throws SQLException {
    try (Statement st = connection.createStatement()) {
      st.execute("PRAGMA journal_mode=WAL");
      st.execute("PRAGMA busy_timeout=5000");
      st.execute("PRAGMA foreign_keys=ON");
      st.execute("PRAGMA synchronous=NORMAL");
    }
  }

  public SqliteInboxStore inboxStore() {
    return inboxStore;
  }

  public SqliteApiCredentialStore credentials() {
    return credentialStore;
  }

  @Override
  public void close() {
    lock.lock();
    try {
      try (Statement st = connection.createStatement()) {
        st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
      } catch (SQLException ignored) {
        // 关库失败不覆盖主流程；数据库文件本身已经持久化。
      }
      try {
        connection.close();
      } catch (SQLException ignored) {
        // 同上。
      }
    } finally {
      lock.unlock();
    }
  }
}

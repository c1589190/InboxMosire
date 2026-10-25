package io.mosire.inbox.server;

import io.mosire.inbox.core.InboxService;
import io.mosire.inbox.sqlite.SqliteDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 后台进程装配：SQLite + service + HTTP/SSE + MCP + claim sweeper。 */
public final class InboxDaemon implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(InboxDaemon.class);

  private final ServerConfig config;
  private final SqliteDatabase database;
  private final InboxService service;
  private final SseNotifier notifier;
  private final InboxHttpServer httpServer;
  private final InboxMcpServer mcpServer;
  private final ScheduledExecutorService sweeper;
  private volatile boolean closed;

  private InboxDaemon(
      ServerConfig config,
      SqliteDatabase database,
      InboxService service,
      SseNotifier notifier,
      InboxHttpServer httpServer,
      InboxMcpServer mcpServer,
      ScheduledExecutorService sweeper) {
    this.config = config;
    this.database = database;
    this.service = service;
    this.notifier = notifier;
    this.httpServer = httpServer;
    this.mcpServer = mcpServer;
    this.sweeper = sweeper;
  }

  public static InboxDaemon start(ServerConfig config) throws IOException {
    SqliteDatabase database = SqliteDatabase.open(databasePath(config));
    try {
      SseNotifier notifier = new SseNotifier();
      InboxService service =
          new InboxService(database.inboxStore(), Clock.systemUTC(), notifier);
      service.createInbox(config.defaultInbox());
      // Occupancy 表示“当前活着的连接/会话占用”，不是持久业务事实。
      service.clearAllOccupancy();

      InboxHttpServer http =
          new InboxHttpServer(
              config.apiHost(),
              config.apiPort(),
              service,
              database.credentials(),
              notifier,
              config.sseHeartbeat());
      InboxMcpServer mcp =
          new InboxMcpServer(
              config.mcpHost(), config.mcpPort(), config.mcpPath(), service, config.defaultInbox());
      try {
        http.start();
        mcp.start();
      } catch (IOException | RuntimeException e) {
        http.close();
        mcp.close();
        throw e;
      }

      ScheduledExecutorService sweeper =
          Executors.newSingleThreadScheduledExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "inbox-claim-sweeper");
                thread.setDaemon(true);
                return thread;
              });
      sweeper.scheduleWithFixedDelay(
          () -> {
            try {
              service.sweepExpired();
            } catch (RuntimeException e) {
              LOG.error("claim sweeper 失败", e);
            }
          },
          5,
          5,
          TimeUnit.SECONDS);
      service.sweepExpired();
      LOG.info(
          "InboxMosire 已启动 data={} api=http://{}:{}/v1 mcp=http://{}:{}{} sweeper=5s",
          databasePath(config),
          config.apiHost(),
          http.boundPort(),
          config.mcpHost(),
          mcp.boundPort(),
          config.mcpPath());
      return new InboxDaemon(config, database, service, notifier, http, mcp, sweeper);
    } catch (IOException | RuntimeException e) {
      database.close();
      throw e;
    }
  }

  public static Path databasePath(ServerConfig config) {
    return config.dataDir().resolve("inbox.db").toAbsolutePath().normalize();
  }

  public int apiPort() {
    return httpServer.boundPort();
  }

  public int mcpPort() {
    return mcpServer.boundPort();
  }

  public ServerConfig config() {
    return config;
  }

  public InboxService service() {
    return service;
  }

  public SqliteDatabase database() {
    return database;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    sweeper.shutdownNow();
    try {
      sweeper.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    mcpServer.close();
    httpServer.close();
    database.close();
    LOG.info("InboxMosire 已停止");
  }
}

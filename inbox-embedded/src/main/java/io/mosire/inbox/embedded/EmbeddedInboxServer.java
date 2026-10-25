package io.mosire.inbox.embedded;

import io.mosire.inbox.client.InboxClient;
import io.mosire.inbox.server.ApiScopes;
import io.mosire.inbox.server.InboxDaemon;
import io.mosire.inbox.server.ServerConfig;
import io.mosire.inbox.sqlite.IssuedCredential;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在调用方进程内启动一个独立 headless InboxMosire server。
 *
 * <pre>{@code
 * try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start("app-a")) {
 *   URI api = inbox.apiUrl();
 *   URI mcp = inbox.mcpUrl();
 *   String token = inbox.provisionerToken();
 *   InboxClient client = inbox.client();
 * }
 * }</pre>
 *
 * 每个实例拥有独立 SQLite、随机回环 API/MCP 端口和 provisioner token；close()
 * 会停止 HTTP/MCP/sweeper/数据库，并按选项清理临时数据。
 */
public final class EmbeddedInboxServer implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(EmbeddedInboxServer.class);

  private static final Set<String> PROVISIONER_SCOPES =
      Set.of(
          ApiScopes.INBOX_ENSURE,
          ApiScopes.INBOX_OCCUPY,
          ApiScopes.CREDENTIALS_ISSUE_SELF,
          ApiScopes.ITEMS_WRITE,
          ApiScopes.ITEMS_READ,
          ApiScopes.ITEMS_MANAGE,
          ApiScopes.STATUS_READ,
          ApiScopes.EVENTS_READ);

  private final InboxDaemon daemon;
  private final Path dataDir;
  private final boolean deleteDataOnClose;
  private final String inboxId;
  private final String provisionerToken;
  private final InboxClient client;
  private final AtomicBoolean closed = new AtomicBoolean();

  private EmbeddedInboxServer(
      InboxDaemon daemon,
      Path dataDir,
      boolean deleteDataOnClose,
      String inboxId,
      String provisionerToken) {
    this.daemon = daemon;
    this.dataDir = dataDir;
    this.deleteDataOnClose = deleteDataOnClose;
    this.inboxId = inboxId;
    this.provisionerToken = provisionerToken;
    this.client =
        InboxClient.builder()
            .baseUrl(URI.create(apiUrlString()))
            .token(provisionerToken)
            .build();
  }

  /** 临时目录 + main inbox + random ports 的一行启动。 */
  public static EmbeddedInboxServer start() throws IOException {
    return start("main");
  }

  /** 临时目录 + 指定 inbox + random ports 的一行启动。 */
  public static EmbeddedInboxServer start(String inboxId) throws IOException {
    return start(EmbeddedInboxOptions.builder().inboxId(inboxId).build());
  }

  public static EmbeddedInboxServer start(EmbeddedInboxOptions options) throws IOException {
    Objects.requireNonNull(options, "options");
    Path effectiveDataDir = options.dataDir();
    boolean temporary = effectiveDataDir == null;
    if (temporary) {
      effectiveDataDir = Files.createTempDirectory("inbox-embedded-");
    } else {
      Files.createDirectories(effectiveDataDir);
    }
    boolean deleteOnClose =
        options.deleteDataOnClose() == null
            ? temporary
            : options.deleteDataOnClose();
    ServerConfig config =
        new ServerConfig(
            effectiveDataDir,
            "127.0.0.1",
            0,
            "127.0.0.1",
            0,
            "/mcp",
            options.heartbeat(),
            options.inboxId());
    InboxDaemon daemon = null;
    try {
      daemon = InboxDaemon.start(config);
      IssuedCredential issued =
          daemon
              .database()
              .credentials()
              .issue(
                  "embedded-" + options.inboxId() + "-provisioner",
                  PROVISIONER_SCOPES,
                  options.inboxId(),
                  Set.of(),
                  null,
                  "inbox-embedded");
      String apiUrl =
          "http://" + config.apiHost() + ":" + daemon.apiPort() + "/v1";
      LOG.info(
          "Embedded InboxMosire 已启动 api={} mcp={} inbox={}",
          apiUrl,
          "http://" + config.mcpHost() + ":" + daemon.mcpPort() + config.mcpPath(),
          options.inboxId());
      return new EmbeddedInboxServer(
          daemon, effectiveDataDir, deleteOnClose, options.inboxId(), issued.token());
    } catch (IOException | RuntimeException e) {
      if (daemon != null) {
        daemon.close();
      }
      if (deleteOnClose) {
        deleteRecursively(effectiveDataDir);
      }
      throw e;
    }
  }

  /** 调用方 InboxClient 的 base URL，不含 /v1。 */
  public URI apiUrl() {
    return URI.create("http://127.0.0.1:" + daemon.apiPort());
  }

  public URI mcpUrl() {
    return URI.create("http://127.0.0.1:" + daemon.mcpPort() + "/mcp");
  }

  public String inboxId() {
    return inboxId;
  }

  /** 一次性 provisioner token；不得写日志，调用方应放入本机 secret store。 */
  public String provisionerToken() {
    return provisionerToken;
  }

  /** 已经配置好 apiUrl + provisioner token 的客户端。 */
  public InboxClient client() {
    return client;
  }

  public Path dataDir() {
    return dataDir;
  }

  private String apiUrlString() {
    return "http://127.0.0.1:" + daemon.apiPort();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    daemon.close();
    if (deleteDataOnClose) {
      deleteRecursively(dataDir);
    }
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // 清理失败不能覆盖主流程；调用方可保留目录继续排障。
        }
      });
    } catch (IOException ignored) {
      // 同上。
    }
  }
}

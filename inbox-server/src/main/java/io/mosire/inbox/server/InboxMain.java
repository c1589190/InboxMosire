package io.mosire.inbox.server;

import io.mosire.inbox.core.InboxService;
import io.mosire.inbox.core.InboxStatus;
import io.mosire.inbox.sqlite.ApiCredential;
import io.mosire.inbox.sqlite.IssuedCredential;
import io.mosire.inbox.sqlite.SqliteDatabase;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/** inboxd CLI：init / serve / inbox create / token issue / token revoke / doctor。 */
public final class InboxMain {
  public static void main(String[] args) {
    try {
      int exit = run(args);
      if (exit != 0) {
        System.exit(exit);
      }
    } catch (Exception e) {
      System.err.println("错误: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      System.exit(1);
    }
  }

  static int run(String[] args) throws Exception {
    if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
      printUsage();
      return 0;
    }
    String command = args[0];
    Args parsed = Args.parse(args, 1);
    return switch (command) {
      case "init" -> init(parsed);
      case "serve" -> serve(parsed);
      case "inbox" -> inbox(parsed);
      case "token" -> token(parsed);
      case "doctor" -> doctor(parsed);
      default -> {
        System.err.println("未知命令: " + command);
        printUsage();
        yield 2;
      }
    };
  }

  private static int init(Args args) throws Exception {
    Path dataDir = dataDir(args);
    try (SqliteDatabase db = SqliteDatabase.open(dataDir.resolve("inbox.db"))) {
      db.inboxStore().createInbox("main");
      System.out.println("已初始化: " + dataDir.toAbsolutePath().normalize());
      System.out.println("数据库: " + dataDir.resolve("inbox.db").toAbsolutePath().normalize());
      return 0;
    }
  }

  private static int serve(Args args) throws Exception {
    ServerConfig config = serverConfig(args);
    InboxDaemon daemon = InboxDaemon.start(config);
    CountDownLatch stopped = new CountDownLatch(1);
    Thread hook =
        new Thread(
            () -> {
              daemon.close();
              stopped.countDown();
            },
            "inbox-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);
    System.out.println("API: http://" + config.apiHost() + ":" + daemon.apiPort() + "/v1");
    System.out.println(
        "MCP: http://" + config.mcpHost() + ":" + daemon.mcpPort() + config.mcpPath());
    System.out.println("Data: " + InboxDaemon.databasePath(config));
    System.out.println("按 Ctrl-C 停止。");
    try {
      stopped.await();
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException ignored) {
        // JVM 正在关闭。
      }
      daemon.close();
    }
    return 0;
  }

  private static int inbox(Args args) throws Exception {
    if (!"create".equals(args.positional())) {
      System.err.println("用法: inbox create --id <id> [--data-dir <dir>]");
      return 2;
    }
    String id = args.required("id");
    Path dataDir = dataDir(args);
    try (SqliteDatabase db = SqliteDatabase.open(dataDir.resolve("inbox.db"))) {
      InboxService service =
          new InboxService(db.inboxStore(), Clock.systemUTC(), io.mosire.inbox.core.InboxChangeNotifier.NONE);
      service.createInbox(id);
      System.out.println("已创建 inbox: " + id);
      return 0;
    }
  }

  private static int token(Args args) throws Exception {
    String sub = args.positional();
    Path dataDir = dataDir(args);
    if ("issue".equals(sub)) {
      String label = args.required("label");
      String inbox = args.get("inbox", "main");
      Set<String> scopes = new LinkedHashSet<>(args.all("scope"));
      if (scopes.isEmpty()) {
        System.err.println("token issue 至少需要一个 --scope");
        return 2;
      }
      Set<String> channels = new LinkedHashSet<>(args.all("channel"));
      Instant expiresAt = expiry(args);
      try (SqliteDatabase db = SqliteDatabase.open(dataDir.resolve("inbox.db"))) {
        IssuedCredential issued =
            db.credentials().issue(label, scopes, inbox, channels, expiresAt, "cli");
        printIssued(issued);
        return 0;
      }
    }
    if ("list".equals(sub)) {
      String inbox = args.get("inbox", null);
      try (SqliteDatabase db = SqliteDatabase.open(dataDir.resolve("inbox.db"))) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("credentials", db.credentials().list(inbox).stream().map(InboxMain::credentialMap).toList());
        System.out.println(InboxJson.MAPPER.writeValueAsString(result));
        return 0;
      }
    }
    if ("rotate".equals(sub)) {
      String id = args.required("id");
      try (SqliteDatabase db = SqliteDatabase.open(dataDir.resolve("inbox.db"))) {
        IssuedCredential issued = db.credentials().rotate(id, "cli");
        printIssued(issued);
        return 0;
      }
    }
    if ("revoke".equals(sub)) {
      String id = args.required("id");
      try (SqliteDatabase db = SqliteDatabase.open(dataDir.resolve("inbox.db"))) {
        boolean revoked = db.credentials().revoke(id);
        System.out.println(revoked ? "已撤销: " + id : "未找到或已撤销: " + id);
        return revoked ? 0 : 1;
      }
    }
    System.err.println("用法: token issue|list|rotate|revoke ...");
    return 2;
  }

  private static void printIssued(IssuedCredential issued) throws Exception {
    System.out.println("credential_id=" + issued.credential().id());
    System.out.println("fingerprint=" + issued.credential().fingerprint());
    System.out.println("token=" + issued.token());
    System.out.println("只显示这一次；请放进本机 secret 配置。");
  }

  private static Map<String, Object> credentialMap(ApiCredential c) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", c.id());
    map.put("label", c.label());
    map.put("scopes", c.scopes());
    map.put("inbox", c.inboxId());
    map.put("channels", c.channels());
    map.put("fingerprint", c.fingerprint());
    map.put("createdAt", c.createdAt() == null ? null : c.createdAt().toString());
    map.put("expiresAt", c.expiresAt() == null ? null : c.expiresAt().toString());
    map.put("lastUsedAt", c.lastUsedAt() == null ? null : c.lastUsedAt().toString());
    map.put("revokedAt", c.revokedAt() == null ? null : c.revokedAt().toString());
    map.put("rotatedFrom", c.rotatedFrom());
    map.put("createdBy", c.createdBy());
    return map;
  }

  private static Instant expiry(Args args) {
    String raw = args.get("expires-in-seconds", null);
    if (raw == null) {
      return null;
    }
    long seconds;
    try {
      seconds = Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--expires-in-seconds 必须是整数");
    }
    if (seconds < 1 || seconds > 31_536_000L) {
      throw new IllegalArgumentException("--expires-in-seconds 必须在 1..31536000");
    }
    return Instant.now().plusSeconds(seconds);
  }

  private static int doctor(Args args) throws Exception {
    Path dataDir = dataDir(args);
    try (SqliteDatabase db = SqliteDatabase.open(dataDir.resolve("inbox.db"))) {
      InboxService service =
          new InboxService(
              db.inboxStore(),
              Clock.systemUTC(),
              io.mosire.inbox.core.InboxChangeNotifier.NONE);
      boolean main = service.inboxExists("main");
      InboxStatus status = main ? service.status("main", Set.of()) : null;
      System.out.println("data_dir=" + dataDir.toAbsolutePath().normalize());
      System.out.println("db=" + dataDir.resolve("inbox.db").toAbsolutePath().normalize());
      System.out.println("main_exists=" + main);
      if (status != null) {
        System.out.println(
            "main_revision="
                + status.revision()
                + " available="
                + status.available()
                + " claimed="
                + status.claimed());
      }
      return main ? 0 : 1;
    }
  }

  private static ServerConfig serverConfig(Args args) {
    ServerConfig defaults = ServerConfig.defaults(dataDir(args));
    String apiHost = args.get("api-host", env("INBOX_API_HOST", defaults.apiHost()));
    int apiPort = intOpt(args, "api-port", envInt("INBOX_API_PORT", defaults.apiPort()));
    String mcpHost = args.get("mcp-host", env("INBOX_MCP_HOST", defaults.mcpHost()));
    int mcpPort = intOpt(args, "mcp-port", envInt("INBOX_MCP_PORT", defaults.mcpPort()));
    String mcpPath = args.get("mcp-path", env("INBOX_MCP_PATH", defaults.mcpPath()));
    int heartbeat =
        intOpt(
            args,
            "sse-heartbeat-seconds",
            envInt("INBOX_SSE_HEARTBEAT_SECONDS", (int) defaults.sseHeartbeat().toSeconds()));
    String defaultInbox = args.get("default-inbox", env("INBOX_DEFAULT_INBOX", defaults.defaultInbox()));
    return new ServerConfig(
        defaults.dataDir(),
        apiHost,
        apiPort,
        mcpHost,
        mcpPort,
        mcpPath,
        Duration.ofSeconds(heartbeat),
        defaultInbox);
  }

  private static Path dataDir(Args args) {
    return Path.of(args.get("data-dir", env("INBOX_DATA_DIR", "data")));
  }

  private static int intOpt(Args args, String key, int defaultValue) {
    String value = args.get(key, null);
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--" + key + " 必须是整数");
    }
  }

  private static int envInt(String name, int defaultValue) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " 必须是整数");
    }
  }

  private static String env(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static void printUsage() {
    System.out.println(
        """
        用法:
          inboxd init [--data-dir <dir>]
          inboxd serve [--data-dir <dir>] [--api-host 127.0.0.1] [--api-port 18765]
                       [--mcp-host 127.0.0.1] [--mcp-port 18766] [--mcp-path /mcp]
                       [--sse-heartbeat-seconds 15] [--default-inbox main]
          inboxd inbox create --id <id> [--data-dir <dir>]
          inboxd token issue --label <label> --scope <scope> [--scope ...]
                           [--inbox main] [--channel <channel> ...]
                           [--expires-in-seconds N] [--data-dir <dir>]
          inboxd token list [--inbox main] [--data-dir <dir>]
          inboxd token rotate --id <credential_id> [--data-dir <dir>]
          inboxd token revoke --id <credential_id> [--data-dir <dir>]
          inboxd doctor [--data-dir <dir>]

        scopes:
          items:write              producer push
          items:read               list available items
          items:manage             claim/renew/complete/release
          events:read              SSE event stream
          status:read              board snapshot
          inbox:ensure             ensure exact-bound inbox exists
          inbox:occupy             mark/clear advisory inbox occupancy
          credentials:issue:self   issue child credentials for exact-bound inbox
          admin:inboxes            create/ensure any inbox
          admin:credentials        manage any credential

        安全: v1 只允许 127.0.0.1/localhost。token 只显示一次，不写日志。
        """);
  }

  static final class Args {
    private final Map<String, List<String>> options;
    private final String positional;

    private Args(Map<String, List<String>> options, String positional) {
      this.options = options;
      this.positional = positional;
    }

    static Args parse(String[] args, int start) {
      Map<String, List<String>> options = new LinkedHashMap<>();
      List<String> positional = new ArrayList<>();
      for (int i = start; i < args.length; i++) {
        String token = args[i];
        if (token.startsWith("--")) {
          String key = token.substring(2);
          if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
            throw new IllegalArgumentException("--" + key + " 缺少值");
          }
          options.computeIfAbsent(key, ignored -> new ArrayList<>()).add(args[++i]);
        } else {
          positional.add(token);
        }
      }
      if (positional.size() > 1) {
        throw new IllegalArgumentException("只允许一个子命令: " + positional);
      }
      return new Args(options, positional.isEmpty() ? "" : positional.getFirst());
    }

    String get(String key, String defaultValue) {
      List<String> values = options.get(key);
      if (values == null || values.isEmpty()) {
        return defaultValue;
      }
      if (values.size() > 1) {
        throw new IllegalArgumentException("--" + key + " 只能给一次");
      }
      return values.getFirst();
    }

    String required(String key) {
      String value = get(key, null);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("缺少 --" + key);
      }
      return value;
    }

    List<String> all(String key) {
      return options.getOrDefault(key, List.of());
    }

    String positional() {
      return positional;
    }
  }
}

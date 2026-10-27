package io.mosire.inbox.embedded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.inbox.client.InboxModels;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class EmbeddedInboxServerTest {
  @TempDir Path temp;

  @Test
  void oneLineStartCreatesIsolatedServerInboxAndToken() throws Exception {
    Path dataDir;
    try (EmbeddedInboxServer server = EmbeddedInboxServer.start("app-a")) {
      dataDir = server.dataDir();
      assertThat(Files.isDirectory(dataDir)).isTrue();
      assertThat(server.apiUrl().getPort()).isPositive();
      assertThat(server.mcpUrl().getPort()).isPositive();
      assertThat(server.mcpUrl().getPath()).isEqualTo("/mcp");
      assertThat(server.inboxId()).isEqualTo("app-a");
      assertThat(server.provisionerToken()).startsWith("inbox_");

      assertThat(server.client().status("app-a", Set.of()).inbox()).isEqualTo("app-a");
      InboxModels.PushResult pushed =
          server
              .client()
              .push(
                  "app-a",
                  InboxModels.PushItem.builder("ticket")
                      .summary("embedded")
                      .content(java.util.Map.of("body", "EMBEDDED_BODY"))
                      .build());
      assertThat(pushed.itemId()).startsWith("itm_");
      InboxModels.ClaimResult claimed =
          server.client().claim("app-a", List.of(pushed.itemId()), Duration.ofSeconds(60));
      assertThat(claimed.claimed()).hasSize(1);
      assertThat(
              server
                  .client()
                  .complete(
                      "app-a",
                      List.of(
                          new InboxModels.Completion(
                              claimed.claimed().getFirst().claimToken(), "completed", "ok")))
                  .available())
          .isZero();
    }
    assertThat(Files.exists(dataDir)).as("temporary embedded dataDir cleaned on close").isFalse();
  }

  @Test
  void customDataDirIsKeptByDefault() throws Exception {
    Path dataDir = temp.resolve("custom-data");
    EmbeddedInboxOptions options =
        EmbeddedInboxOptions.builder().dataDir(dataDir).inboxId("app-b").build();
    try (EmbeddedInboxServer server = EmbeddedInboxServer.start(options)) {
      assertThat(server.client().status("app-b", Set.of()).inbox()).isEqualTo("app-b");
    }
    assertThat(Files.isRegularFile(dataDir.resolve("inbox.db"))).isTrue();
  }

  @Test
  void defaultStartUsesRandomPortsAndMainInbox() throws Exception {
    try (EmbeddedInboxServer server = EmbeddedInboxServer.start()) {
      assertThat(server.inboxId()).isEqualTo("main");
      assertThat(server.apiPort()).isPositive();
      assertThat(server.mcpPort()).isPositive();
      assertThat(server.apiUrl().getPort()).isEqualTo(server.apiPort());
      assertThat(server.mcpUrl().getPort()).isEqualTo(server.mcpPort());
      assertThat(server.mcpUrl().getPath()).isEqualTo("/mcp");
      assertThat(server.serverConfig().apiHost()).isEqualTo("127.0.0.1");
      assertThat(server.serverConfig().mcpHost()).isEqualTo("127.0.0.1");
      assertThat(server.serverConfig().apiPort()).isZero();
      assertThat(server.serverConfig().mcpPort()).isZero();
      assertThat(server.serverConfig().mcpPath()).isEqualTo("/mcp");
      assertThat(server.claimSweepInterval()).isEqualTo(Duration.ofSeconds(5));
      assertThat(server.client().status("main", Set.of()).inbox()).isEqualTo("main");
    }
  }

  @Test
  void customMcpPathAndDurationsAreReflectedInUrlsAndDiagnostics() throws Exception {
    EmbeddedInboxOptions options =
        EmbeddedInboxOptions.builder()
            .inboxId("app-path")
            .mcpPath("/custom/mcp")
            .heartbeat(Duration.ofSeconds(7))
            .claimSweepInterval(Duration.ofMillis(250))
            .build();
    try (EmbeddedInboxServer server = EmbeddedInboxServer.start(options)) {
      assertThat(server.mcpUrl().getPath()).isEqualTo("/custom/mcp");
      assertThat(server.mcpUrl().getPort()).isEqualTo(server.mcpPort());
      assertThat(server.serverConfig().mcpPath()).isEqualTo("/custom/mcp");
      assertThat(server.serverConfig().sseHeartbeat()).isEqualTo(Duration.ofSeconds(7));
      assertThat(server.claimSweepInterval()).isEqualTo(Duration.ofMillis(250));
    }
  }

  @Test
  void fixedPortsAreBoundAndReleasedOnClose() throws Exception {
    int apiPort = freePort();
    int mcpPort = freePort();
    EmbeddedInboxOptions options =
        EmbeddedInboxOptions.builder()
            .inboxId("app-fixed")
            .apiPort(apiPort)
            .mcpPort(mcpPort)
            .build();
    try (EmbeddedInboxServer server = EmbeddedInboxServer.start(options)) {
      assertThat(server.apiPort()).isEqualTo(apiPort);
      assertThat(server.mcpPort()).isEqualTo(mcpPort);
      assertThat(server.apiUrl().getPort()).isEqualTo(apiPort);
      assertThat(server.mcpUrl().getPort()).isEqualTo(mcpPort);
    }
    try (ServerSocket ignoredApi = new ServerSocket(apiPort, 50, InetAddress.getByName("127.0.0.1"));
        ServerSocket ignoredMcp = new ServerSocket(mcpPort, 50, InetAddress.getByName("127.0.0.1"))) {
      assertThat(ignoredApi.isBound()).isTrue();
      assertThat(ignoredMcp.isBound()).isTrue();
    }
  }

  @Test
  void fixedPortCollisionFailsClosed() throws Exception {
    int occupiedPort = freePort();
    try (ServerSocket occupied = new ServerSocket(
        occupiedPort, 50, InetAddress.getByName("127.0.0.1"))) {
      assertThat(occupied.isBound()).isTrue();
      EmbeddedInboxOptions options =
          EmbeddedInboxOptions.builder()
              .inboxId("app-collision")
              .apiPort(occupiedPort)
              .build();
      assertThatThrownBy(() -> EmbeddedInboxServer.start(options))
          .isInstanceOf(java.io.IOException.class);
    }
  }

  @Test
  void invalidOptionsFailClosedAtBuildTime() {
    assertThatThrownBy(() -> EmbeddedInboxOptions.builder().apiHost("0.0.0.0").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EmbeddedInboxOptions.builder().mcpHost(" ").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EmbeddedInboxOptions.builder().apiPort(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EmbeddedInboxOptions.builder().mcpPort(65536).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EmbeddedInboxOptions.builder().mcpPath("mcp").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EmbeddedInboxOptions.builder().mcpPath("/mcp?x=1").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EmbeddedInboxOptions.builder().mcpPath("/mcp#frag").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EmbeddedInboxOptions.builder().heartbeat(Duration.ZERO).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> EmbeddedInboxOptions.builder().claimSweepInterval(Duration.ofMillis(-1)).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void explicitDeleteDataOnCloseFalseKeepsDatabase() throws Exception {
    Path dataDir = temp.resolve("kept-data");
    EmbeddedInboxOptions options =
        EmbeddedInboxOptions.builder()
            .dataDir(dataDir)
            .inboxId("app-kept")
            .deleteDataOnClose(false)
            .build();
    try (EmbeddedInboxServer server = EmbeddedInboxServer.start(options)) {
      assertThat(server.serverConfig().dataDir()).isEqualTo(dataDir.toAbsolutePath().normalize());
    }
    assertThat(Files.isRegularFile(dataDir.resolve("inbox.db"))).isTrue();
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }

}

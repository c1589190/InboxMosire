package io.mosire.inbox.embedded;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.inbox.client.InboxModels;
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
}

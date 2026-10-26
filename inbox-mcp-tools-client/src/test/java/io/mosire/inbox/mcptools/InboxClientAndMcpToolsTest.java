package io.mosire.inbox.mcptools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.mcp.AgentToMcpServer;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.inbox.client.InboxClient;
import io.mosire.inbox.client.InboxClientException;
import io.mosire.inbox.client.InboxModels;
import io.mosire.inbox.server.InboxDaemon;
import io.mosire.inbox.server.ServerConfig;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class InboxClientAndMcpToolsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path temp;

  @Test
  void clientCanProvisionOperateAndIssueBindingDescriptor() throws Exception {
    try (InboxDaemon daemon = daemon()) {
      URI base = URI.create("http://127.0.0.1:" + daemon.apiPort());
      InboxClient bootstrap = client(daemon, base, provisionerToken(daemon, "app-a", false));
      InboxModels.EnsureResult ensured = bootstrap.ensureInbox("app-a");
      assertThat(ensured.created()).isTrue();
      assertThat(bootstrap.ensureInbox("app-a").created()).isFalse();

      InboxModels.PushResult pushed =
          bootstrap.push(
              "app-a",
              InboxModels.PushItem.builder("ticket")
                  .summary("from client")
                  .content(Map.of("body", "CLIENT_BODY"))
                  .build());
      assertThat(pushed.itemId()).startsWith("itm_");
      assertThat(bootstrap.list("app-a", Set.of(), 10).count()).isEqualTo(1);
      InboxModels.ClaimResult claimed =
          bootstrap.claim(
              "app-a", List.of(pushed.itemId()), Duration.ofSeconds(60));
      assertThat(claimed.claimed()).hasSize(1);
      assertThat(claimed.claimed().getFirst().content()).isNotNull();
      String claimToken = claimed.claimed().getFirst().claimToken();
      InboxModels.CompleteResult completed =
          bootstrap.complete(
              "app-a",
              List.of(new InboxModels.Completion(claimToken, "completed", "ok")));
      assertThat(completed.available()).isZero();
      assertThat(bootstrap.status("app-a", Set.of()).available()).isZero();

      Path tokenFile = temp.resolve("secrets/dsh-app-a.token");
      InboxModels.BindingDescriptor binding =
          bootstrap.issueBinding(
              "app-a", "dsh-session-S1", Set.of(), tokenFile, Duration.ofHours(1));
      assertThat(binding.inboxId()).isEqualTo("app-a");
      assertThat(binding.adapterTokenRef()).startsWith("file:");
      assertThat(binding.toString()).doesNotContain("inbox_");
      String adapterToken = Files.readString(tokenFile);
      assertThat(adapterToken).startsWith("inbox_");
      InboxClient adapterClient =
          InboxClient.forTokenFile(base, tokenFile);
      assertThat(adapterClient.status("app-a", Set.of()).inbox()).isEqualTo("app-a");
      assertThat(adapterClient.whoAmI().scopes()).contains("items:read");
      assertThat(adapterClient.list("app-a", Set.of(), 3).count()).isZero();

      String noItemsToken =
          daemon
              .database()
              .credentials()
              .issue(
                  "no-items-read",
                  Set.of("status:read", "events:read", "inbox:occupy"),
                  "app-a",
                  Set.of(),
                  null,
                  "test")
              .token();
      assertThatThrownBy(
              () -> client(daemon, base, noItemsToken).list("app-a", Set.of(), 3))
          .isInstanceOf(InboxClientException.class)
          .extracting(error -> ((InboxClientException) error).statusCode())
          .isEqualTo(403);

      InboxModels.Occupancy occupied = bootstrap.occupy("app-a", "program-a-session");
      assertThat(occupied.occupied()).isTrue();
      assertThat(occupied.occupiedBy()).isEqualTo("program-a-session");
      assertThat(bootstrap.occupancy("app-a").occupied()).isTrue();
      assertThat(bootstrap.status("app-a", Set.of()).occupied()).isTrue();
      InboxModels.Occupancy vacated = bootstrap.vacate("app-a");
      assertThat(vacated.occupied()).isFalse();

      InboxModels.CredentialView who = bootstrap.whoAmI();
      assertThat(who.inbox()).isEqualTo("app-a");
      assertThat(who.fingerprint()).startsWith("sha256:");
      assertThat(who.scopes()).contains("items:write");
      assertThat(new InboxClientException(401, "UNAUTHORIZED", "no")).isNotNull();
    }
  }

  @Test
  void programOwnedMcpExposesInboxToolsWithoutRawToken() throws Exception {
    try (InboxDaemon daemon = daemon()) {
      URI base = URI.create("http://127.0.0.1:" + daemon.apiPort());
      String token = provisionerToken(daemon, "app-a", false);
      InboxClient client = client(daemon, base, token);
      client.ensureInbox("app-a");

      ToolRegistry registry = new ToolRegistry();
      registry.registerAll(new InboxClientMcpTools(client, "app-a").all());
      ToolContext caller =
          ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST));
      try (AgentToMcpServer server =
              AgentToMcpServer.startHttp(
                  0,
                  "/mcp",
                  registry,
                  "program-inbox",
                  "0.1.0",
                  caller,
                  ToolCallAuthorizer.of(new ToolExecutionGuard()));
          McpSyncClient mcp = newClient(server.boundPort())) {
        mcp.initialize();
        assertThat(mcp.listTools().tools())
            .extracting(McpSchema.Tool::name)
            .contains(
                "inbox_status",
                "inbox_rest",
                "inbox_list",
                "inbox_claim",
                "inbox_complete",
                "inbox_release",
                "inbox_renew",
                "inbox_push",
                "inbox_view",
                "inbox_update",
                "inbox_append",
                "inbox_delete",
                "inbox_occupancy",
                "inbox_occupy",
                "inbox_vacate",
                "inbox_credential_info");

        McpSchema.CallToolResult pushed =
            mcp.callTool(
                new McpSchema.CallToolRequest(
                    "inbox_push",
                    Map.of(
                        "channel", "ticket",
                        "summary", "program mcp",
                        "content", Map.of("body", "MCP_BODY"))));
        assertThat(pushed.isError()).isFalse();
        JsonNode pushJson = JSON.readTree(wireText(pushed));
        String itemId = pushJson.get("itemId").asText();

        McpSchema.CallToolResult listed =
            mcp.callTool(new McpSchema.CallToolRequest("inbox_list", Map.of()));
        assertThat(listed.isError()).as(wireText(listed)).isFalse();
        assertThat(JSON.readTree(wireText(listed)).get("count").asInt()).isEqualTo(1);

        McpSchema.CallToolResult claimed =
            mcp.callTool(
                new McpSchema.CallToolRequest(
                    "inbox_claim",
                    Map.of("itemIds", List.of(itemId), "leaseSeconds", 60)));
        JsonNode claimJson = JSON.readTree(wireText(claimed));
        String claimToken = claimJson.get("claimed").get(0).get("claimToken").asText();
        assertThat(claimJson.get("claimed").get(0).get("summary").asText())
            .isEqualTo("program mcp");
        assertThat(claimJson.get("claimed").get(0).get("content").get("body").asText())
            .isEqualTo("MCP_BODY");

        McpSchema.CallToolResult completed =
            mcp.callTool(
                new McpSchema.CallToolRequest(
                    "inbox_complete",
                    Map.of(
                        "restSeconds", 30,
                        "completions",
                        List.of(Map.of("claimToken", claimToken, "outcome", "completed")))));
        assertThat(JSON.readTree(wireText(completed)).get("available").asInt()).isZero();

        McpSchema.CallToolResult info =
            mcp.callTool(new McpSchema.CallToolRequest("inbox_credential_info", Map.of()));
        String infoText = wireText(info);
        assertThat(infoText).contains("sha256:");
        assertThat(infoText).doesNotContain(token);

        McpSchema.CallToolResult occupied =
            mcp.callTool(
                new McpSchema.CallToolRequest(
                    "inbox_occupy", Map.of("occupiedBy", "program-session")));
        assertThat(occupied.isError()).isFalse();
        assertThat(JSON.readTree(wireText(occupied)).get("occupied").asBoolean()).isTrue();
        McpSchema.CallToolResult occupancy =
            mcp.callTool(new McpSchema.CallToolRequest("inbox_occupancy", Map.of()));
        assertThat(JSON.readTree(wireText(occupancy)).get("occupiedBy").asText())
            .isEqualTo("program-session");
        McpSchema.CallToolResult vacated =
            mcp.callTool(new McpSchema.CallToolRequest("inbox_vacate", Map.of()));
        assertThat(JSON.readTree(wireText(vacated)).get("occupied").asBoolean()).isFalse();
      }
    }
  }


  @Test
  void channelScopedClientCannotClaimOtherChannel() throws Exception {
    try (InboxDaemon daemon = daemon()) {
      URI base = URI.create("http://127.0.0.1:" + daemon.apiPort());
      InboxClient bootstrap = client(daemon, base, provisionerToken(daemon, "app-a", false));
      bootstrap.ensureInbox("app-a");
      InboxModels.PushResult ticket =
          bootstrap.push(
              "app-a", InboxModels.PushItem.builder("ticket").summary("ticket").build());
      InboxModels.PushResult tweet =
          bootstrap.push(
              "app-a", InboxModels.PushItem.builder("tweet").summary("tweet").build());

      String scopedToken =
          daemon
              .database()
              .credentials()
              .issue(
                  "ticket-reader",
                  Set.of("items:read", "items:manage"),
                  "app-a",
                  Set.of("ticket"),
                  null,
                  "test")
              .token();
      InboxClient scoped = client(daemon, base, scopedToken);
      assertThat(scoped.list("app-a", Set.of(), 10).items())
          .extracting(InboxModels.ItemSummary::itemId)
          .containsExactly(ticket.itemId())
          .doesNotContain(tweet.itemId());
      InboxModels.ClaimResult result =
          scoped.claim("app-a", List.of(tweet.itemId()), Duration.ofSeconds(60));
      assertThat(result.claimed()).isEmpty();
      assertThat(result.rejected()).singleElement().extracting(InboxModels.Rejection::code)
          .isEqualTo("ITEM_UNAVAILABLE");
    }
  }


  @Test
  void clientCanViewUpdateAppendAndDeleteAvailableItems() throws Exception {
    try (InboxDaemon daemon = daemon()) {
      URI base = URI.create("http://127.0.0.1:" + daemon.apiPort());
      InboxClient client = client(daemon, base, provisionerToken(daemon, "app-a", false));
      client.ensureInbox("app-a");

      InboxModels.PushResult pushed =
          client.push(
              "app-a",
              InboxModels.PushItem.builder("ticket")
                  .summary("initial summary")
                  .content(Map.of("body", "initial content"))
                  .build());
      InboxModels.ItemDetail viewed = client.view("app-a", pushed.itemId());
      assertThat(viewed.summary()).isEqualTo("initial summary");
      assertThat(String.valueOf(viewed.content())).contains("initial content");

      InboxModels.ItemMutation updated =
          client.update(
              "app-a",
              pushed.itemId(),
              new InboxModels.UpdateItem(
                  "updated summary", "updated content", null, null, 42, null));
      assertThat(updated.deleted()).isFalse();
      InboxModels.ItemDetail updatedView = client.view("app-a", pushed.itemId());
      assertThat(updatedView.summary()).isEqualTo("updated summary");
      assertThat(updatedView.content()).isEqualTo("updated content");
      assertThat(updatedView.priority()).isEqualTo(42);

      client.append(
          "app-a",
          pushed.itemId(),
          new InboxModels.AppendItem(" appended", "final summary", 50, null));
      InboxModels.ItemDetail appended = client.view("app-a", pushed.itemId());
      assertThat(appended.summary()).isEqualTo("final summary");
      assertThat(appended.content()).isEqualTo("updated content\n appended");
      assertThat(appended.priority()).isEqualTo(50);

      InboxModels.ItemMutation deleted = client.deleteItem("app-a", pushed.itemId(), null);
      assertThat(deleted.deleted()).isTrue();
      assertThatThrownBy(() -> client.view("app-a", pushed.itemId()))
          .isInstanceOf(InboxClientException.class)
          .extracting(error -> ((InboxClientException) error).statusCode())
          .isEqualTo(404);

      InboxModels.PushResult claimedTarget =
          client.push(
              "app-a",
              InboxModels.PushItem.builder("ticket").summary("claimed").build());
      client.claim("app-a", List.of(claimedTarget.itemId()), Duration.ofSeconds(60));
      InboxClientException claimedError =
          catchThrowableOfType(
              () ->
                  client.update(
                      "app-a",
                      claimedTarget.itemId(),
                      new InboxModels.UpdateItem("nope", null, null, null, null, null)),
              InboxClientException.class);
      assertThat(claimedError.statusCode()).isEqualTo(409);
      assertThat(claimedError.code()).isEqualTo("ITEM_STATUS_CONFLICT");
      assertThat(claimedError.details())
          .containsEntry("itemStatus", "claimed")
          .containsKey("allowedActions");
      assertThat(claimedError.details().get("allowedActions").toString())
          .contains("complete", "release");
      assertThat(claimedError.getMessage()).doesNotContain("initial content");

      InboxModels.PushResult staleTarget =
          client.push(
              "app-a",
              InboxModels.PushItem.builder("ticket").summary("stale target").build());
      InboxModels.ItemDetail staleView = client.view("app-a", staleTarget.itemId());
      InboxClientException revisionError =
          catchThrowableOfType(
              () ->
                  client.update(
                      "app-a",
                      staleTarget.itemId(),
                      new InboxModels.UpdateItem(
                          "stale update", null, null, null, null, staleView.revision() + 1)),
              InboxClientException.class);
      assertThat(revisionError.statusCode()).isEqualTo(409);
      assertThat(revisionError.code()).isEqualTo("ITEM_REVISION_CONFLICT");
      assertThat(
              ((Number) revisionError.details().get("expectedRevision")).longValue())
          .isEqualTo(staleView.revision() + 1);
      assertThat(
              ((Number) revisionError.details().get("actualRevision")).longValue())
          .isEqualTo(staleView.revision());
      assertThat(revisionError.details()).containsEntry("itemStatus", "available");

      InboxModels.PushResult doneTarget =
          client.push(
              "app-a",
              InboxModels.PushItem.builder("ticket").summary("done target").build());
      InboxModels.ClaimResult doneClaim =
          client.claim("app-a", List.of(doneTarget.itemId()), Duration.ofSeconds(60));
      client.complete(
          "app-a",
          List.of(
              new InboxModels.Completion(
                  doneClaim.claimed().getFirst().claimToken(), "completed", "done")));
      InboxClientException doneError =
          catchThrowableOfType(
              () -> client.deleteItem("app-a", doneTarget.itemId(), null),
              InboxClientException.class);
      assertThat(doneError.statusCode()).isEqualTo(409);
      assertThat(doneError.code()).isEqualTo("ITEM_STATUS_CONFLICT");
      assertThat(doneError.details()).containsEntry("itemStatus", "done");
    }
  }

  private InboxDaemon daemon() throws Exception {
    return InboxDaemon.start(
        new ServerConfig(
            temp,
            "127.0.0.1",
            0,
            "127.0.0.1",
            0,
            "/mcp",
            Duration.ofSeconds(30),
            "main"));
  }

  private static InboxClient client(InboxDaemon daemon, URI base, String token) {
    return InboxClient.builder().baseUrl(base).token(token).build();
  }

  private String provisionerToken(InboxDaemon daemon, String inbox, boolean admin) {
    java.util.Set<String> scopes =
        admin
            ? Set.of(
                "admin:inboxes",
                "admin:credentials",
                "inbox:ensure",
                "credentials:issue:self",
                "items:write",
                "items:read",
                "items:manage",
                "status:read",
                "events:read",
                "inbox:occupy")
            : Set.of(
                "inbox:ensure",
                "credentials:issue:self",
                "items:write",
                "items:read",
                "items:manage",
                "status:read",
                "events:read",
                "inbox:occupy");
    return daemon
        .database()
        .credentials()
        .issue(
            "test-program",
            scopes,
            inbox,
            Set.of(),
            null,
            "test")
        .token();
  }

  private static McpSyncClient newClient(int port) {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
            .endpoint("/mcp")
            .resumableStreams(false)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    return McpClient.sync(transport).build();
  }

  private static String wireText(McpSchema.CallToolResult result) {
    return result.content().getFirst() instanceof McpSchema.TextContent text
        ? text.text()
        : result.content().toString();
  }
}

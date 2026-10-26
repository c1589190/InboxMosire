package io.mosire.inbox.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class InboxMcpServerTest {
  @TempDir Path temp;

  @Test
  void officialClientCanPushListClaimAndComplete() throws Exception {
    try (InboxDaemon daemon =
        InboxDaemon.start(
            new ServerConfig(
                temp,
                "127.0.0.1",
                0,
                "127.0.0.1",
                0,
                "/mcp",
                Duration.ofSeconds(30),
                "main"))) {
      HttpClientStreamableHttpTransport transport =
          HttpClientStreamableHttpTransport.builder(
                  "http://127.0.0.1:" + daemon.mcpPort())
              .endpoint("/mcp")
              .resumableStreams(false)
              .connectTimeout(Duration.ofSeconds(5))
              .build();
      try (McpSyncClient client = McpClient.sync(transport).build()) {
        client.initialize();
        assertThat(client.listTools().tools())
            .extracting(McpSchema.Tool::name)
            .containsExactlyInAnyOrder(
                "inbox_status",
                "inbox_list",
                "inbox_claim",
                "inbox_renew",
                "inbox_complete",
                "inbox_release",
                "inbox_push",
                "inbox_view",
                "inbox_update",
                "inbox_append",
                "inbox_delete")
            .doesNotContain("inbox_wait");

        McpSchema.CallToolResult pushed =
            client.callTool(
                call(
                    "inbox_push",
                    Map.of(
                        "channel", "ticket",
                        "kind", "reply",
                        "summary", "MCP task",
                        "content", Map.of("body", "payload-secret"),
                        "dedupeKey", "mcp-1")));
        assertThat(pushed.isError()).isFalse();
        JsonNode push = json(pushed);
        assertThat(push.get("itemId").asText()).startsWith("itm_");
        String itemId = push.get("itemId").asText();

        McpSchema.CallToolResult listed =
            client.callTool(call("inbox_list", Map.of()));
        JsonNode list = json(listed);
        assertThat(list.get("count").asInt()).isEqualTo(1);
        assertThat(list.toString()).doesNotContain("payload-secret");
        assertThat(list.get("items").get(0).get("summary").asText()).isEqualTo("MCP task");

        McpSchema.CallToolResult viewed =
            client.callTool(call("inbox_view", Map.of("itemId", itemId)));
        JsonNode view = json(viewed);
        assertThat(view.get("summary").asText()).isEqualTo("MCP task");
        assertThat(view.get("content").get("body").asText()).isEqualTo("payload-secret");

        McpSchema.CallToolResult updated =
            client.callTool(
                call(
                    "inbox_update",
                    Map.of(
                        "itemId",
                        itemId,
                        "summary",
                        "MCP task updated",
                        "content",
                        "line-one",
                        "priority",
                        7)));
        assertThat(updated.isError()).as(wireText(updated)).isFalse();
        McpSchema.CallToolResult appended =
            client.callTool(
                call("inbox_append", Map.of("itemId", itemId, "content", "line-two")));
        assertThat(appended.isError()).as(wireText(appended)).isFalse();
        McpSchema.CallToolResult updatedView =
            client.callTool(call("inbox_view", Map.of("itemId", itemId)));
        JsonNode updatedJson = json(updatedView);
        assertThat(updatedJson.get("summary").asText()).isEqualTo("MCP task updated");
        assertThat(updatedJson.get("content").asText()).contains("line-one").contains("line-two");
        assertThat(updatedJson.get("priority").asInt()).isEqualTo(7);

        McpSchema.CallToolResult claimed =
            client.callTool(
                call(
                    "inbox_claim", Map.of("itemIds", List.of(itemId), "leaseSeconds", 60)));
        JsonNode claim = json(claimed);
        assertThat(claim.get("claimed")).hasSize(1);
        String claimToken = claim.get("claimed").get(0).get("claimToken").asText();
        assertThat(claim.get("claimed").get(0).get("summary").asText())
            .isEqualTo("MCP task updated");
        assertThat(claim.get("claimed").get(0).get("content").asText())
            .contains("line-one")
            .contains("line-two");

        McpSchema.CallToolResult claimedDelete =
            client.callTool(call("inbox_delete", Map.of("itemId", itemId)));
        assertThat(claimedDelete.isError()).isTrue();
        String claimedDeleteText = wireText(claimedDelete);
        assertThat(claimedDeleteText)
            .contains("ITEM_STATUS_CONFLICT")
            .contains("claimed")
            .contains("complete")
            .contains("release")
            .doesNotContain("line-one");

        McpSchema.CallToolResult completed =
            client.callTool(
                call(
                    "inbox_complete",
                    Map.of(
                        "completions",
                        List.of(
                            Map.of(
                                "claimToken",
                                claimToken,
                                "outcome",
                                "completed",
                                "summary",
                                "done")))));
        JsonNode complete = json(completed);
        assertThat(complete.get("available").asInt()).isZero();
        assertThat(complete.get("completed").get(0).get("idempotent").asBoolean()).isFalse();

        McpSchema.CallToolResult secondPush =
            client.callTool(
                call(
                    "inbox_push",
                    Map.of("channel", "ticket", "summary", "delete me", "content", "x")));
        String secondId = json(secondPush).get("itemId").asText();
        McpSchema.CallToolResult deleted =
            client.callTool(call("inbox_delete", Map.of("itemId", secondId)));
        assertThat(deleted.isError()).as(wireText(deleted)).isFalse();
        assertThat(json(deleted).get("deleted").asBoolean()).isTrue();
        assertThat(json(client.callTool(call("inbox_list", Map.of()))).get("count").asInt())
            .isZero();

        McpSchema.CallToolResult bad =
            client.callTool(call("inbox_claim", Map.of()));
        assertThat(bad.isError()).isTrue();
        assertThat(wireText(bad)).contains("input validation failed");
      }
    }
  }

  @Test
  void toolsCanRejectInvalidLeaseAndUnknownClaimToken() throws Exception {
    try (InboxDaemon daemon =
        InboxDaemon.start(
            new ServerConfig(
                temp,
                "127.0.0.1",
                0,
                "127.0.0.1",
                0,
                "/mcp",
                Duration.ofSeconds(30),
                "main"))) {
      HttpClientStreamableHttpTransport transport =
          HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + daemon.mcpPort())
              .endpoint("/mcp")
              .resumableStreams(false)
              .connectTimeout(Duration.ofSeconds(5))
              .build();
      try (McpSyncClient client = McpClient.sync(transport).build()) {
        client.initialize();
        McpSchema.CallToolResult badLease =
            client.callTool(
                call(
                    "inbox_claim",
                    Map.of("itemIds", List.of("itm_missing"), "leaseSeconds", 1)));
        assertThat(badLease.isError()).isTrue();
        assertThat(wireText(badLease)).startsWith("[mosire:code=INVALID_ARGUMENT]");

        McpSchema.CallToolResult unknownClaim =
            client.callTool(
                call(
                    "inbox_release", Map.of("claimTokens", List.of("clm_missing"))));
        assertThat(unknownClaim.isError()).isFalse();
        JsonNode result = json(unknownClaim);
        assertThat(result.get("rejected").get(0).get("code").asText()).isEqualTo("CLAIM_INVALID");
      }
    }
  }

  private static McpSchema.CallToolRequest call(String name, Map<String, Object> arguments) {
    return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
  }

  private static JsonNode json(McpSchema.CallToolResult result) throws Exception {
    return InboxJson.MAPPER.readTree(wireText(result));
  }

  private static String wireText(McpSchema.CallToolResult result) {
    var content = result.content();
    return content.getFirst() instanceof McpSchema.TextContent text ? text.text() : content.toString();
  }
}

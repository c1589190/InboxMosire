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
                "inbox_push")
            .doesNotContain("inbox_wait");

        McpSchema.CallToolResult pushed =
            client.callTool(
                new McpSchema.CallToolRequest(
                    "inbox_push",
                    Map.of(
                        "channel", "ticket",
                        "kind", "reply",
                        "title", "MCP task",
                        "payload", Map.of("body", "payload-secret"),
                        "dedupeKey", "mcp-1")));
        assertThat(pushed.isError()).isFalse();
        JsonNode push = json(pushed);
        assertThat(push.get("itemId").asText()).startsWith("itm_");
        String itemId = push.get("itemId").asText();

        McpSchema.CallToolResult listed =
            client.callTool(new McpSchema.CallToolRequest("inbox_list", Map.of()));
        JsonNode list = json(listed);
        assertThat(list.get("count").asInt()).isEqualTo(1);
        assertThat(list.toString()).doesNotContain("payload-secret");

        McpSchema.CallToolResult claimed =
            client.callTool(
                new McpSchema.CallToolRequest(
                    "inbox_claim", Map.of("itemIds", List.of(itemId), "leaseSeconds", 60)));
        JsonNode claim = json(claimed);
        assertThat(claim.get("claimed")).hasSize(1);
        String claimToken = claim.get("claimed").get(0).get("claimToken").asText();
        assertThat(claim.get("claimed").get(0).get("payload").get("body").asText())
            .isEqualTo("payload-secret");

        McpSchema.CallToolResult completed =
            client.callTool(
                new McpSchema.CallToolRequest(
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

        McpSchema.CallToolResult bad =
            client.callTool(new McpSchema.CallToolRequest("inbox_claim", Map.of()));
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
                new McpSchema.CallToolRequest(
                    "inbox_claim",
                    Map.of("itemIds", List.of("itm_missing"), "leaseSeconds", 1)));
        assertThat(badLease.isError()).isTrue();
        assertThat(wireText(badLease)).startsWith("[mosire:code=INVALID_ARGUMENT]");

        McpSchema.CallToolResult unknownClaim =
            client.callTool(
                new McpSchema.CallToolRequest(
                    "inbox_release", Map.of("claimTokens", List.of("clm_missing"))));
        assertThat(unknownClaim.isError()).isFalse();
        JsonNode result = json(unknownClaim);
        assertThat(result.get("rejected").get(0).get("code").asText()).isEqualTo("CLAIM_INVALID");
      }
    }
  }

  private static JsonNode json(McpSchema.CallToolResult result) throws Exception {
    return InboxJson.MAPPER.readTree(wireText(result));
  }

  private static String wireText(McpSchema.CallToolResult result) {
    var content = result.content();
    return content.getFirst() instanceof McpSchema.TextContent text ? text.text() : content.toString();
  }
}

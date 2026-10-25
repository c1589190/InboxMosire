package io.mosire.inbox.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboxAdminApiTest {
  @TempDir Path temp;
  private InboxDaemon daemon;
  private HttpClient client;
  private String base;
  private String bootstrapToken;
  private String adminToken;

  @BeforeEach
  void start() throws Exception {
    daemon =
        InboxDaemon.start(
            new ServerConfig(
                temp,
                "127.0.0.1",
                0,
                "127.0.0.1",
                0,
                "/mcp",
                Duration.ofSeconds(30),
                "main"));
    base = "http://127.0.0.1:" + daemon.apiPort();
    bootstrapToken =
        daemon
            .database()
            .credentials()
            .issue(
                "bootstrap-app-a",
                Set.of(
                    ApiScopes.INBOX_ENSURE,
                    ApiScopes.CREDENTIALS_ISSUE_SELF,
                    ApiScopes.ITEMS_WRITE,
                    ApiScopes.STATUS_READ,
                    ApiScopes.EVENTS_READ),
                "app-a",
                Set.of(),
                null,
                "test")
            .token();
    adminToken =
        daemon
            .database()
            .credentials()
            .issue(
                "admin",
                Set.of(ApiScopes.ADMIN_CREDENTIALS, ApiScopes.ADMIN_INBOXES),
                "*",
                Set.of(),
                null,
                "test")
            .token();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stop() {
    if (daemon != null) {
      daemon.close();
    }
  }

  @Test
  void ensureIssueRotateRevokeAndMetadataStayScoped() throws Exception {
    HttpResponse<String> created = request("PUT", "/v1/inboxes/app-a", bootstrapToken, "");
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(InboxJson.MAPPER.readTree(created.body()).get("created").asBoolean()).isTrue();

    HttpResponse<String> again = request("PUT", "/v1/inboxes/app-a", bootstrapToken, "");
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(InboxJson.MAPPER.readTree(again.body()).get("created").asBoolean()).isFalse();

    HttpResponse<String> forbidden =
        request("PUT", "/v1/inboxes/app-b", bootstrapToken, "");
    assertThat(forbidden.statusCode()).isEqualTo(403);

    HttpResponse<String> issued =
        request(
            "POST",
            "/v1/admin/inboxes/app-a/credentials",
            bootstrapToken,
            """
            {"label":"app-a-mcp","scopes":["items:write","status:read"],"channels":[],"expiresInSeconds":3600}
            """);
    assertThat(issued.statusCode()).isEqualTo(201);
    JsonNode issuedJson = InboxJson.MAPPER.readTree(issued.body());
    String childToken = issuedJson.get("token").asText();
    String childId = issuedJson.get("credential").get("id").asText();
    assertThat(childToken).startsWith("inbox_");
    assertThat(issuedJson.get("credential").get("fingerprint").asText()).startsWith("sha256:");

    HttpResponse<String> who =
        request("GET", "/v1/admin/whoami", childToken, null);
    assertThat(who.statusCode()).isEqualTo(200);
    assertThat(who.body()).contains("app-a").doesNotContain(childToken);

    HttpResponse<String> pushed =
        request(
            "POST",
            "/v1/inboxes/app-a/items",
            childToken,
            """
            {"channel":"ticket","kind":"reply","title":"admin flow"}
            """);
    assertThat(pushed.statusCode()).isEqualTo(201);

    HttpResponse<String> listed =
        request("GET", "/v1/admin/credentials?inbox=app-a", adminToken, null);
    assertThat(listed.statusCode()).isEqualTo(200);
    JsonNode listedJson = InboxJson.MAPPER.readTree(listed.body());
    assertThat(listedJson.get("credentials")).hasSizeGreaterThanOrEqualTo(2);
    assertThat(listed.body()).doesNotContain(childToken).doesNotContain("token_hash");

    HttpResponse<String> rotated =
        request("POST", "/v1/admin/credentials/" + childId + "/rotate", bootstrapToken, "");
    assertThat(rotated.statusCode()).isEqualTo(200);
    JsonNode rotatedJson = InboxJson.MAPPER.readTree(rotated.body());
    String rotatedToken = rotatedJson.get("token").asText();
    String rotatedId = rotatedJson.get("credential").get("id").asText();
    assertThat(rotatedToken).isNotEqualTo(childToken);

    assertThat(request("POST", "/v1/inboxes/app-a/items", childToken,
        "{\"channel\":\"ticket\",\"title\":\"old\"}").statusCode()).isEqualTo(401);
    assertThat(request("POST", "/v1/inboxes/app-a/items", rotatedToken,
        "{\"channel\":\"ticket\",\"title\":\"new\"}").statusCode()).isEqualTo(201);

    HttpResponse<String> revoked =
        request("POST", "/v1/admin/credentials/" + rotatedId + "/revoke", bootstrapToken, "");
    assertThat(revoked.statusCode()).isEqualTo(200);
    assertThat(request("POST", "/v1/inboxes/app-a/items", rotatedToken,
        "{\"channel\":\"ticket\",\"title\":\"after-revoke\"}").statusCode()).isEqualTo(401);
  }

  @Test
  void issueCannotBroadenScopesOrChannels() throws Exception {
    request("PUT", "/v1/inboxes/app-a", bootstrapToken, "");
    HttpResponse<String> broad =
        request(
            "POST",
            "/v1/admin/inboxes/app-a/credentials",
            bootstrapToken,
            """
            {"label":"broad","scopes":["admin:credentials"]}
            """);
    assertThat(broad.statusCode()).isEqualTo(403);
  }

  private HttpResponse<String> request(String method, String path, String token, String body)
      throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    HttpRequest request =
        "GET".equals(method)
            ? builder.GET().build()
            : builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}

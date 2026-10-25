package io.mosire.inbox.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboxHttpServerTest {
  @TempDir Path temp;
  private InboxDaemon daemon;
  private HttpClient client;
  private String apiBase;
  private String producerToken;
  private String adapterToken;
  private String ticketOnlyToken;

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
    apiBase = "http://127.0.0.1:" + daemon.apiPort() + "/v1/inboxes/main";
    producerToken =
        daemon
            .database()
            .credentials()
            .issue("producer", Set.of(ApiScopes.ITEMS_WRITE, ApiScopes.STATUS_READ), "main", Set.of())
            .token();
    adapterToken =
        daemon
            .database()
            .credentials()
            .issue(
                "adapter",
                Set.of(ApiScopes.EVENTS_READ, ApiScopes.STATUS_READ),
                "main",
                Set.of())
            .token();
    ticketOnlyToken =
        daemon
            .database()
            .credentials()
            .issue(
                "ticket-adapter",
                Set.of(ApiScopes.EVENTS_READ, ApiScopes.STATUS_READ, ApiScopes.INBOX_OCCUPY),
                "main",
                Set.of("ticket"))
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
  void pushRequiresAuthAndChannelScopeAndIsVisibleInStatus() throws Exception {
    HttpResponse<String> unauthorized =
        client.send(
            HttpRequest.newBuilder(URI.create(apiBase + "/items"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"channel\":\"ticket\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(unauthorized.statusCode()).isEqualTo(401);

    HttpResponse<String> forbidden =
        client.send(
            HttpRequest.newBuilder(URI.create(apiBase + "/items"))
                .header("Authorization", "Bearer " + ticketOnlyToken)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"channel\":\"tweet\",\"kind\":\"review\",\"title\":\"x\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(forbidden.statusCode()).isEqualTo(403);

    HttpResponse<String> created =
        client.send(
            pushRequest(
                "{\"channel\":\"ticket\",\"kind\":\"reply\",\"title\":\"ticket 1\","
                    + "\"payload\":{\"secret\":\"TOP_SECRET_BODY\"},\"dedupeKey\":\"t1\"}"),
            HttpResponse.BodyHandlers.ofString());
    assertThat(created.statusCode()).isEqualTo(201);
    JsonNode push = InboxJson.MAPPER.readTree(created.body());
    assertThat(push.get("itemId").asText()).startsWith("itm_");
    assertThat(push.get("duplicate").asBoolean()).isFalse();

    HttpResponse<String> status =
        client.send(
            HttpRequest.newBuilder(URI.create(apiBase + "/status"))
                .header("Authorization", "Bearer " + ticketOnlyToken)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(status.statusCode()).isEqualTo(200);
    JsonNode body = InboxJson.MAPPER.readTree(status.body());
    assertThat(body.get("available").asInt()).isEqualTo(1);
    assertThat(body.get("channels").has("ticket")).isTrue();
    assertThat(status.body()).doesNotContain("TOP_SECRET_BODY");
  }

  @Test
  void sseStreamsCloudEventsWithoutPayloadAndReplaysFromLastEventId() throws Exception {
    HttpRequest streamRequest =
        HttpRequest.newBuilder(URI.create(apiBase + "/events"))
            .header("Authorization", "Bearer " + adapterToken)
            .header("Accept", "text/event-stream")
            .GET()
            .build();
    HttpResponse<InputStream> stream =
        client.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(stream.statusCode()).isEqualTo(200);

    CompletableFuture<String> firstFrame = CompletableFuture.supplyAsync(() -> readFrame(stream.body()));
    HttpResponse<String> pushed =
        client.send(
            pushRequest(
                "{\"channel\":\"ticket\",\"kind\":\"reply\",\"title\":\"ticket\","
                    + "\"payload\":{\"secret\":\"TOP_SECRET_BODY\"}}"),
            HttpResponse.BodyHandlers.ofString());
    assertThat(pushed.statusCode()).isEqualTo(201);

    String frame = firstFrame.get(5, TimeUnit.SECONDS);
    assertThat(frame).contains("event: inbox.changed").contains("data: ");
    assertThat(frame).doesNotContain("TOP_SECRET_BODY").doesNotContain("\"title\"");
    JsonNode event = eventFromFrame(frame);
    assertThat(event.get("specversion").asText()).isEqualTo("1.0");
    assertThat(event.get("type").asText()).isEqualTo(CloudEvents.TYPE);
    assertThat(event.get("data").get("changedChannels").get(0).asText()).isEqualTo("ticket");
    String lastId = event.get("id").asText();
    assertThat(lastId).startsWith("evt_");
    String sseId = frame.lines().filter(line -> line.startsWith("id: ")).findFirst().orElseThrow();
    long cursor = Long.parseLong(sseId.substring(4));
    stream.body().close();

    HttpResponse<String> second =
        client.send(
            pushRequest("{\"channel\":\"ticket\",\"kind\":\"reply\",\"title\":\"second\"}"),
            HttpResponse.BodyHandlers.ofString());
    assertThat(second.statusCode()).isEqualTo(201);

    HttpRequest replayRequest =
        HttpRequest.newBuilder(URI.create(apiBase + "/events"))
            .header("Authorization", "Bearer " + adapterToken)
            .header("Accept", "text/event-stream")
            .header("Last-Event-ID", Long.toString(cursor))
            .GET()
            .build();
    HttpResponse<InputStream> replay =
        client.send(replayRequest, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(replay.statusCode()).isEqualTo(200);
    String replayed = CompletableFuture.supplyAsync(() -> readFrame(replay.body())).get(5, TimeUnit.SECONDS);
    replay.body().close();
    assertThat(replayed).contains("event: inbox.changed");
    assertThat(eventFromFrame(replayed).get("data").get("revision").asLong())
        .isGreaterThan(event.get("data").get("revision").asLong());
  }


  @Test
  void occupancyIsExposedInStatusAndCanBeCleared() throws Exception {
    HttpRequest.Builder occupiedBuilder =
        HttpRequest.newBuilder(URI.create(apiBase + "/occupancy"))
            .header("Authorization", "Bearer " + ticketOnlyToken)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"occupiedBy\":\"session-S1\"}"));
    HttpResponse<String> occupied =
        client.send(occupiedBuilder.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(occupied.statusCode()).isEqualTo(200);
    JsonNode first = InboxJson.MAPPER.readTree(occupied.body());
    assertThat(first.get("occupied").asBoolean()).isTrue();
    assertThat(first.get("occupiedBy").asText()).isEqualTo("session-S1");

    HttpResponse<String> status =
        client.send(
            HttpRequest.newBuilder(URI.create(apiBase + "/status"))
                .header("Authorization", "Bearer " + ticketOnlyToken)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    JsonNode statusJson = InboxJson.MAPPER.readTree(status.body());
    assertThat(statusJson.get("occupied").asBoolean()).isTrue();
    assertThat(statusJson.get("occupiedBy").asText()).isEqualTo("session-S1");

    HttpResponse<String> vacated =
        client.send(
            HttpRequest.newBuilder(URI.create(apiBase + "/occupancy"))
                .header("Authorization", "Bearer " + ticketOnlyToken)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(InboxJson.MAPPER.readTree(vacated.body()).get("occupied").asBoolean()).isFalse();
  }

  @Test
  void channelScopedAdapterNeverSeesOtherChannels() throws Exception {
    HttpRequest streamRequest =
        HttpRequest.newBuilder(URI.create(apiBase + "/events"))
            .header("Authorization", "Bearer " + ticketOnlyToken)
            .header("Accept", "text/event-stream")
            .GET()
            .build();
    HttpResponse<InputStream> stream =
        client.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(stream.statusCode()).isEqualTo(200);
    CompletableFuture<String> frameFuture =
        CompletableFuture.supplyAsync(() -> readFrame(stream.body()));

    client.send(
        pushRequest("{\"channel\":\"tweet\",\"kind\":\"review\",\"title\":\"hidden\"}"),
        HttpResponse.BodyHandlers.ofString());
    client.send(
        pushRequest("{\"channel\":\"ticket\",\"kind\":\"reply\",\"title\":\"visible\"}"),
        HttpResponse.BodyHandlers.ofString());

    String frame = frameFuture.get(5, TimeUnit.SECONDS);
    stream.body().close();
    JsonNode event = eventFromFrame(frame);
    assertThat(event.get("data").get("changedChannels").toString()).contains("ticket").doesNotContain("tweet");
    assertThat(event.get("data").get("channels").has("ticket")).isTrue();
    assertThat(event.get("data").get("channels").has("tweet")).isFalse();
    assertThat(frame).doesNotContain("hidden");
  }

  private HttpRequest pushRequest(String body) {
    return HttpRequest.newBuilder(URI.create(apiBase + "/items"))
        .header("Authorization", "Bearer " + producerToken)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
  }

  private static String readFrame(InputStream in) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      StringBuilder frame = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          return frame.toString();
        }
        frame.append(line).append('\n');
      }
      return frame.toString();
    } catch (Exception e) {
      throw new IllegalStateException("读取 SSE 帧失败", e);
    }
  }

  private static JsonNode eventFromFrame(String frame) throws Exception {
    String data =
        frame.lines()
            .filter(line -> line.startsWith("data: "))
            .findFirst()
            .orElseThrow()
            .substring("data: ".length());
    return InboxJson.MAPPER.readTree(data);
  }
}

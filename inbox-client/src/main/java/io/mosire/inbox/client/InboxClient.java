package io.mosire.inbox.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * InboxMosire 共享 Java 客户端。程序用它 provision inbox、签发受限 token、push、
 * 代理 Agent MCP 的 inbox 操作；它不拥有服务端状态，也不依赖服务端模块。
 */
public final class InboxClient {
  public static final String CLIENT_VERSION = "0.1.0";
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private final URI baseUri;
  private final TokenSource tokenSource;
  private final HttpClient httpClient;
  private final Duration requestTimeout;

  private InboxClient(Builder builder) {
    this.baseUri = normalizeBaseUri(builder.baseUrl);
    this.tokenSource = Objects.requireNonNull(builder.tokenSource, "tokenSource");
    this.requestTimeout = Objects.requireNonNull(builder.requestTimeout, "requestTimeout");
    this.httpClient =
        builder.httpClient != null
            ? builder.httpClient
            : HttpClient.newBuilder().connectTimeout(requestTimeout).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static InboxClient forTokenFile(URI baseUrl, Path tokenFile) {
    return builder().baseUrl(baseUrl).tokenSource(TokenSource.file(tokenFile)).build();
  }

  public InboxModels.EnsureResult ensureInbox(String inboxId) {
    return requestJson("PUT", "/v1/inboxes/" + encode(inboxId), null, InboxModels.EnsureResult.class, 200, 201);
  }

  public InboxModels.PushResult push(String inboxId, InboxModels.PushItem item) {
    return requestJson(
        "POST", "/v1/inboxes/" + encode(inboxId) + "/items", item, InboxModels.PushResult.class, 200, 201);
  }

  public InboxModels.InboxStatus status(String inboxId, Set<String> channels) {
    String query = channelQuery(channels);
    return requestJson(
        "GET",
        "/v1/inboxes/" + encode(inboxId) + "/status" + query,
        null,
        InboxModels.InboxStatus.class,
        200);
  }

  public InboxModels.Occupancy occupancy(String inboxId) {
    return requestJson(
        "GET",
        "/v1/inboxes/" + encode(inboxId) + "/occupancy",
        null,
        InboxModels.Occupancy.class,
        200);
  }

  public InboxModels.Occupancy occupy(String inboxId, String occupiedBy) {
    return requestJson(
        "PUT",
        "/v1/inboxes/" + encode(inboxId) + "/occupancy",
        Map.of("occupiedBy", occupiedBy == null ? "" : occupiedBy),
        InboxModels.Occupancy.class,
        200);
  }

  public InboxModels.Occupancy vacate(String inboxId) {
    return requestJson(
        "DELETE",
        "/v1/inboxes/" + encode(inboxId) + "/occupancy",
        null,
        InboxModels.Occupancy.class,
        200);
  }

  public InboxModels.ItemList list(String inboxId, Set<String> channels, int limit) {
    String query = channelQuery(channels) + (channels == null || channels.isEmpty() ? "?" : "&") + "limit=" + limit;
    return requestJson(
        "GET", "/v1/inboxes/" + encode(inboxId) + "/items" + query, null, InboxModels.ItemList.class, 200);
  }

  public InboxModels.ClaimResult claim(String inboxId, List<String> itemIds, Duration lease) {
    Map<String, Object> body =
        Map.of("itemIds", itemIds, "leaseSeconds", Math.max(1, lease.toSeconds()));
    return normalizeClaim(
        requestJson(
            "POST",
            "/v1/inboxes/" + encode(inboxId) + "/claims",
            body,
            InboxModels.ClaimResult.class,
            200));
  }

  private static InboxModels.ClaimResult normalizeClaim(InboxModels.ClaimResult result) {
    List<InboxModels.ClaimedItem> claimed = new java.util.ArrayList<>();
    for (InboxModels.ClaimedItem item : result.claimed()) {
      Object payload = item.payload();
      if (payload instanceof String text) {
        try {
          payload = JSON.readTree(text);
        } catch (IOException ignored) {
          // 非 JSON 文本 payload 原样保留。
        }
      }
      claimed.add(
          new InboxModels.ClaimedItem(
              item.itemId(),
              item.channel(),
              item.kind(),
              item.title(),
              payload,
              item.payloadRef(),
              item.priority(),
              item.claimToken(),
              item.claimExpiresAt()));
    }
    return new InboxModels.ClaimResult(
        result.revision(), result.available(), List.copyOf(claimed), result.rejected());
  }

  public InboxModels.RenewResult renew(String inboxId, List<String> claimTokens, Duration lease) {
    Map<String, Object> body =
        Map.of("claimTokens", claimTokens, "leaseSeconds", Math.max(1, lease.toSeconds()));
    return requestJson(
        "POST",
        "/v1/inboxes/" + encode(inboxId) + "/claims/renew",
        body,
        InboxModels.RenewResult.class,
        200);
  }

  public InboxModels.CompleteResult complete(
      String inboxId, List<InboxModels.Completion> completions) {
    return requestJson(
        "POST",
        "/v1/inboxes/" + encode(inboxId) + "/claims/complete",
        Map.of("completions", completions),
        InboxModels.CompleteResult.class,
        200);
  }

  public InboxModels.ReleaseResult release(String inboxId, List<String> claimTokens) {
    return requestJson(
        "POST",
        "/v1/inboxes/" + encode(inboxId) + "/claims/release",
        Map.of("claimTokens", claimTokens),
        InboxModels.ReleaseResult.class,
        200);
  }

  public InboxModels.IssuedCredentialView issueCredential(
      String inboxId, InboxModels.CredentialIssueRequest request) {
    return requestJson(
        "POST",
        "/v1/admin/inboxes/" + encode(inboxId) + "/credentials",
        request,
        InboxModels.IssuedCredentialView.class,
        201);
  }

  public InboxModels.CredentialList listCredentials(String inboxId) {
    String query = inboxId == null || inboxId.isBlank() ? "" : "?inbox=" + encode(inboxId);
    return requestJson(
        "GET", "/v1/admin/credentials" + query, null, InboxModels.CredentialList.class, 200);
  }

  public InboxModels.IssuedCredentialView rotateCredential(String credentialId) {
    return requestJson(
        "POST",
        "/v1/admin/credentials/" + encode(credentialId) + "/rotate",
        null,
        InboxModels.IssuedCredentialView.class,
        200);
  }

  public InboxModels.RevokeResult revokeCredential(String credentialId) {
    return requestJson(
        "POST",
        "/v1/admin/credentials/" + encode(credentialId) + "/revoke",
        null,
        InboxModels.RevokeResult.class,
        200);
  }

  public InboxModels.CredentialView whoAmI() {
    return requestJson("GET", "/v1/admin/whoami", null, InboxModels.CredentialView.class, 200);
  }

  /**
   * program-owned MCP 的标准 provisioning 流程：为 DSH adapter 签发一个只读 token，
   * 写入本地 secret 文件，并返回不含 raw token 的 BindingDescriptor。
   */
  public InboxModels.BindingDescriptor issueBinding(
      String inboxId,
      String label,
      Set<String> channels,
      Path tokenFile,
      Duration ttl) {
    long seconds = Math.max(60, ttl.toSeconds());
    InboxModels.IssuedCredentialView issued =
        issueCredential(
            inboxId,
            new InboxModels.CredentialIssueRequest(
                label,
                Set.of("status:read", "events:read", "items:read", "inbox:occupy"),
                channels == null ? Set.of() : channels,
                seconds));
    TokenStore.writeAtomic(tokenFile, issued.token());
    String ref = "file:" + tokenFile.toAbsolutePath().normalize();
    return new InboxModels.BindingDescriptor(inboxId, baseUri.toString(), ref, channels, label);
  }

  /** 阻塞式 SSE 消费；调用方通过 stop 控制退出。事件 data 保留 CloudEvents JSON 原文。 */
  public void streamEvents(
      String inboxId,
      Long lastEventId,
      Set<String> channels,
      Consumer<InboxEvent> consumer,
      BooleanSupplier stop) {
    Objects.requireNonNull(consumer, "consumer");
    BooleanSupplier stopCheck = stop == null ? () -> false : stop;
    String query = channelQuery(channels);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(
                URI.create(
                    baseUri
                        + "/v1/inboxes/"
                        + encode(inboxId)
                        + "/events"
                        + query))
            .timeout(Duration.ofDays(365))
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer " + tokenSource.readToken())
            .GET();
    if (lastEventId != null) {
      builder.header("Last-Event-ID", Long.toString(lastEventId));
    }
    HttpResponse<java.io.InputStream> response;
    try {
      response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw new InboxClientException("SSE_IO", "SSE 连接失败", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InboxClientException("SSE_INTERRUPTED", "SSE 连接被中断", e);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new InboxClientException(response.statusCode(), "SSE_HTTP_ERROR", "SSE 连接失败");
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String line;
      String eventId = null;
      String eventType = "message";
      StringBuilder data = new StringBuilder();
      while (!stopCheck.getAsBoolean() && (line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          if (data.length() > 0) {
            long seq = eventId == null || eventId.isBlank() ? -1 : Long.parseLong(eventId);
            consumer.accept(new InboxEvent(seq, eventType, data.toString()));
          }
          eventId = null;
          eventType = "message";
          data.setLength(0);
        } else if (line.startsWith("id:")) {
          eventId = line.substring(3).trim();
        } else if (line.startsWith("event:")) {
          eventType = line.substring(6).trim();
        } else if (line.startsWith("data:")) {
          if (data.length() > 0) {
            data.append('\n');
          }
          data.append(line.substring(5).trim());
        }
      }
    } catch (IOException e) {
      throw new InboxClientException("SSE_IO", "SSE 读取失败", e);
    }
  }

  private <T> T requestJson(
      String method, String path, Object body, Class<T> type, int... expectedStatuses) {
    byte[] responseBody;
    int status;
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(baseUri + path))
              .timeout(requestTimeout)
              .header("Authorization", "Bearer " + tokenSource.readToken())
              .header("Accept", "application/json")
              .header("User-Agent", "inbox-client/" + CLIENT_VERSION);
      if (body != null) {
        builder.header("Content-Type", "application/json");
        builder.method(
            method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
      } else {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      }
      HttpResponse<byte[]> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      status = response.statusCode();
      responseBody = response.body();
    } catch (IOException e) {
      throw new InboxClientException("HTTP_IO", "请求 InboxMosire 失败", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InboxClientException("HTTP_INTERRUPTED", "请求被中断", e);
    }
    for (int expected : expectedStatuses) {
      if (status == expected) {
        if (responseBody == null || responseBody.length == 0) {
          return null;
        }
        try {
          return JSON.readValue(responseBody, type);
        } catch (IOException e) {
          throw new InboxClientException("INVALID_RESPONSE", "服务端响应不是预期 JSON", e);
        }
      }
    }
    throw error(status, responseBody);
  }

  private static InboxClientException error(int status, byte[] body) {
    String code = "HTTP_" + status;
    String message = "InboxMosire 请求失败";
    if (body != null && body.length > 0) {
      try {
        JsonNode root = JSON.readTree(body);
        JsonNode error = root.path("error");
        if (!error.isMissingNode()) {
          code = error.path("code").asText(code);
          message = error.path("message").asText(message);
        }
      } catch (IOException ignored) {
        // 不回显服务端原始响应，避免意外包含敏感内容。
      }
    }
    return new InboxClientException(status, code, message);
  }

  private static URI normalizeBaseUri(URI value) {
    Objects.requireNonNull(value, "baseUrl");
    String text = value.toString();
    while (text.endsWith("/")) {
      text = text.substring(0, text.length() - 1);
    }
    return URI.create(text);
  }

  private static String encode(String value) {
    return URLEncoder.encode(Objects.requireNonNull(value, "value"), StandardCharsets.UTF_8)
        .replace("+", "%20");
  }

  private static String channelQuery(Set<String> channels) {
    if (channels == null || channels.isEmpty()) {
      return "";
    }
    return "?channel=" + channels.stream().map(InboxClient::encode).collect(java.util.stream.Collectors.joining(","));
  }

  public record InboxEvent(long seq, String type, String data) {}

  public static final class Builder {
    private URI baseUrl;
    private TokenSource tokenSource;
    private Duration requestTimeout = Duration.ofSeconds(10);
    private HttpClient httpClient;

    public Builder baseUrl(URI value) {
      this.baseUrl = value;
      return this;
    }

    public Builder tokenSource(TokenSource value) {
      this.tokenSource = value;
      return this;
    }

    public Builder token(String value) {
      this.tokenSource = TokenSource.literal(value);
      return this;
    }

    public Builder requestTimeout(Duration value) {
      this.requestTimeout = value;
      return this;
    }

    public Builder httpClient(HttpClient value) {
      this.httpClient = value;
      return this;
    }

    public InboxClient build() {
      return new InboxClient(this);
    }
  }
}

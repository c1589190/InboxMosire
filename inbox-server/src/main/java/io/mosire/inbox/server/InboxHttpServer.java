package io.mosire.inbox.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.inbox.core.InboxException;
import io.mosire.inbox.core.InboxService;
import io.mosire.inbox.core.CompleteResult;
import io.mosire.inbox.core.Completion;
import io.mosire.inbox.core.CompletionOutcome;
import io.mosire.inbox.core.InboxStatus;
import io.mosire.inbox.core.ItemSummary;
import io.mosire.inbox.core.ClaimResult;
import io.mosire.inbox.core.ReleaseResult;
import io.mosire.inbox.core.RenewResult;
import io.mosire.inbox.core.PushCommand;
import io.mosire.inbox.core.PushResult;
import io.mosire.inbox.core.StoredEvent;
import io.mosire.inbox.sqlite.ApiCredential;
import io.mosire.inbox.sqlite.IssuedCredential;
import io.mosire.inbox.sqlite.SqliteApiCredentialStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Producer/Adapter 的标准 HTTP 面：JSON REST + SSE。默认只绑回环。 */
public final class InboxHttpServer implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(InboxHttpServer.class);
  private static final int MAX_BODY = 1024 * 1024;
  private static final int EVENT_BATCH = 100;

  private final String host;
  private final int port;
  private final InboxService service;
  private final SqliteApiCredentialStore credentials;
  private final SseNotifier notifier;
  private final Duration heartbeat;
  private HttpServer server;
  private ExecutorService executor;

  public InboxHttpServer(
      String host,
      int port,
      InboxService service,
      SqliteApiCredentialStore credentials,
      SseNotifier notifier,
      Duration heartbeat) {
    this.host = host;
    this.port = port;
    this.service = service;
    this.credentials = credentials;
    this.notifier = notifier;
    this.heartbeat = heartbeat;
  }

  public synchronized void start() throws IOException {
    if (server != null) {
      return;
    }
    if (!ServerConfig.isLoopback(host)) {
      throw new IllegalArgumentException("v1 HTTP API 仅允许回环地址，拒绝绑定: " + host);
    }
    HttpServer created = HttpServer.create(new InetSocketAddress(host, port), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    created.setExecutor(executor);
    created.createContext("/", this::handle);
    created.start();
    this.server = created;
    LOG.info("Inbox HTTP API 已启动: http://{}:{}/v1", host, boundPort());
  }

  public int boundPort() {
    return server == null ? port : server.getAddress().getPort();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    try {
      if ("GET".equals(exchange.getRequestMethod()) && "/healthz".equals(path)) {
        sendJson(exchange, 200, Map.of("status", "ok"));
        return;
      }
      if ("GET".equals(exchange.getRequestMethod()) && "/readyz".equals(path)) {
        boolean ready = service.inboxExists("main");
        sendJson(exchange, ready ? 200 : 503, Map.of("status", ready ? "ready" : "not-ready"));
        return;
      }
      route(exchange, path);
    } catch (InboxException e) {
      sendJson(exchange, statusFor(e.code()), new ApiError(e.code().name(), e.getMessage()).body());
    } catch (SecurityException e) {
      sendJson(exchange, 403, new ApiError("FORBIDDEN", e.getMessage()).body());
    } catch (IllegalArgumentException e) {
      sendJson(exchange, 422, new ApiError("INVALID_ARGUMENT", e.getMessage()).body());
    } catch (Exception e) {
      LOG.error("HTTP 请求失败 path={}", path, e);
      sendJson(exchange, 500, new ApiError("INTERNAL", "内部错误").body());
    } finally {
      exchange.close();
    }
  }

  private void route(HttpExchange exchange, String path) throws IOException {
    List<String> segments = segments(path);
    if (segments.isEmpty() || !"v1".equals(segments.getFirst())) {
      sendJson(exchange, 404, new ApiError("NOT_FOUND", "路由不存在").body());
      return;
    }
    if (segments.size() == 3
        && "inboxes".equals(segments.get(1))) {
      ensureInbox(exchange, segments.get(2));
      return;
    }
    if (segments.size() == 4 && "inboxes".equals(segments.get(1))) {
      routeInboxResource(exchange, segments.get(2), segments.get(3));
      return;
    }
    if (segments.size() == 3 && "admin".equals(segments.get(1))
        && "whoami".equals(segments.get(2))) {
      whoAmI(exchange);
      return;
    }
    if (segments.size() == 3 && "admin".equals(segments.get(1))
        && "credentials".equals(segments.get(2))) {
      listCredentials(exchange);
      return;
    }
    if (segments.size() == 5 && "admin".equals(segments.get(1))
        && "inboxes".equals(segments.get(2))
        && "credentials".equals(segments.get(4))) {
      issueCredential(exchange, segments.get(3));
      return;
    }
    if (segments.size() == 5 && "admin".equals(segments.get(1))
        && "credentials".equals(segments.get(2))) {
      manageCredential(exchange, segments.get(3), segments.get(4));
      return;
    }
    if (segments.size() == 5
        && "inboxes".equals(segments.get(1))
        && "claims".equals(segments.get(3))) {
      routeClaimAction(exchange, segments.get(2), segments.get(4));
      return;
    }
    sendJson(exchange, 404, new ApiError("NOT_FOUND", "路由不存在").body());
  }

  private void ensureInbox(HttpExchange exchange, String inboxId) throws IOException {
    if (!"PUT".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "PUT");
      return;
    }
    ApiCredential credential = requireAuth(exchange);
    if (credential == null) {
      return;
    }
    if (!credential.allowsInbox(inboxId)) {
      sendJson(exchange, 403, new ApiError("FORBIDDEN", "token 无权访问该 inbox").body());
      return;
    }
    CredentialPolicy.requireEnsure(credential, inboxId);
    boolean created = service.createInbox(inboxId);
    sendJson(exchange, created ? 201 : 200, Map.of("inbox", inboxId, "created", created));
  }

  private void routeInboxResource(HttpExchange exchange, String inboxId, String resource)
      throws IOException {
    ApiCredential credential = requireAuth(exchange);
    if (credential == null) {
      return;
    }
    if (!credential.allowsInbox(inboxId)) {
      sendJson(exchange, 403, new ApiError("FORBIDDEN", "token 无权访问该 inbox").body());
      return;
    }
    if (!service.inboxExists(inboxId)) {
      sendJson(exchange, 404, new ApiError("NOT_FOUND", "inbox 不存在").body());
      return;
    }
    switch (resource) {
      case "items" -> {
        if ("POST".equals(exchange.getRequestMethod())) {
          if (!requiresScope(exchange, credential, ApiScopes.ITEMS_WRITE)) {
            return;
          }
          push(exchange, inboxId, credential);
          return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
          if (!requiresScope(exchange, credential, ApiScopes.ITEMS_READ)) {
            return;
          }
          listItems(exchange, inboxId, credential);
          return;
        }
        methodNotAllowed(exchange, "GET, POST");
      }
      case "claims" -> {
        if (!"POST".equals(exchange.getRequestMethod())) {
          methodNotAllowed(exchange, "POST");
          return;
        }
        if (!requiresScope(exchange, credential, ApiScopes.ITEMS_MANAGE)) {
          return;
        }
        claimItems(exchange, inboxId, credential);
      }
      case "occupancy" -> occupancy(exchange, inboxId, credential);
      case "status" -> {
        if (!"GET".equals(exchange.getRequestMethod())) {
          methodNotAllowed(exchange, "GET");
          return;
        }
        if (!requiresScope(exchange, credential, ApiScopes.STATUS_READ)) {
          return;
        }
        status(exchange, inboxId, credential);
      }
      case "events" -> {
        if (!"GET".equals(exchange.getRequestMethod())) {
          methodNotAllowed(exchange, "GET");
          return;
        }
        if (!requiresScope(exchange, credential, ApiScopes.EVENTS_READ)) {
          return;
        }
        events(exchange, inboxId, credential);
      }
      default -> sendJson(exchange, 404, new ApiError("NOT_FOUND", "资源不存在").body());
    }
  }

  private void whoAmI(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "GET");
      return;
    }
    ApiCredential credential = requireAuth(exchange);
    if (credential == null) {
      return;
    }
    sendJson(exchange, 200, CredentialView.from(credential));
  }

  private void listCredentials(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "GET");
      return;
    }
    ApiCredential credential = requireAuth(exchange);
    if (credential == null) {
      return;
    }
    if (!requiresScope(exchange, credential, ApiScopes.ADMIN_CREDENTIALS)) {
      return;
    }
    String inbox = queryParam(exchange, "inbox");
    List<CredentialView> views =
        credentials.list(inbox).stream().map(CredentialView::from).toList();
    sendJson(exchange, 200, Map.of("credentials", views));
  }

  private void issueCredential(HttpExchange exchange, String inboxId) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "POST");
      return;
    }
    ApiCredential issuer = requireAuth(exchange);
    if (issuer == null) {
      return;
    }
    if (!issuer.allowsInbox(inboxId)) {
      sendJson(exchange, 403, new ApiError("FORBIDDEN", "token 无权访问该 inbox").body());
      return;
    }
    if (!service.inboxExists(inboxId)) {
      sendJson(exchange, 404, new ApiError("NOT_FOUND", "inbox 不存在").body());
      return;
    }
    CredentialPolicy.requireCanIssueFor(issuer, inboxId);
    CredentialIssueRequest request = readCredentialRequest(exchange);
    if (request == null) {
      return;
    }
    Set<String> scopes =
        request.scopes() == null
            ? Set.of()
            : request.scopes().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<String> channels =
        request.channels() == null
            ? Set.of()
            : request.channels().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    CredentialPolicy.validateIssuedScopes(issuer, scopes, channels);
    Instant expiresAt = expiresAt(request.expiresInSeconds());
    IssuedCredential issued =
        credentials.issue(
            request.label(), scopes, inboxId, channels, expiresAt, issuer.id());
    sendJson(
        exchange,
        201,
        new CredentialIssuedResponse(CredentialView.from(issued.credential()), issued.token()));
  }

  private void manageCredential(HttpExchange exchange, String credentialId, String action)
      throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "POST");
      return;
    }
    ApiCredential issuer = requireAuth(exchange);
    if (issuer == null) {
      return;
    }
    ApiCredential target =
        credentials
            .findById(credentialId)
            .orElseThrow(
                () -> new InboxException(InboxException.Code.NOT_FOUND, "credential 不存在"));
    CredentialPolicy.requireCanManage(issuer, target);
    switch (action) {
      case "rotate" -> {
        IssuedCredential issued = credentials.rotate(credentialId, issuer.id());
        sendJson(
            exchange,
            200,
            new CredentialIssuedResponse(CredentialView.from(issued.credential()), issued.token()));
      }
      case "revoke" -> {
        boolean revoked = credentials.revoke(credentialId);
        sendJson(exchange, 200, Map.of("id", credentialId, "revoked", revoked));
      }
      default -> sendJson(exchange, 404, new ApiError("NOT_FOUND", "credential 操作不存在").body());
    }
  }

  private CredentialIssueRequest readCredentialRequest(HttpExchange exchange) throws IOException {
    byte[] body = readBody(exchange);
    if (body == null) {
      return null;
    }
    if (body.length == 0) {
      sendJson(exchange, 400, new ApiError("INVALID_JSON", "请求体为空").body());
      return null;
    }
    try {
      return InboxJson.MAPPER.readValue(body, CredentialIssueRequest.class);
    } catch (Exception e) {
      sendJson(exchange, 400, new ApiError("INVALID_JSON", "请求体不是合法 JSON").body());
      return null;
    }
  }

  private static Instant expiresAt(Long expiresInSeconds) {
    if (expiresInSeconds == null || expiresInSeconds == 0) {
      return null;
    }
    if (expiresInSeconds < 1 || expiresInSeconds > 31_536_000L) {
      throw new IllegalArgumentException("expiresInSeconds 必须在 1..31536000");
    }
    return Instant.now().plusSeconds(expiresInSeconds);
  }

  private ApiCredential requireAuth(HttpExchange exchange) throws IOException {
    ApiCredential credential = authenticate(exchange);
    if (credential == null) {
      sendJson(exchange, 401, new ApiError("UNAUTHORIZED", "缺少或无效 Bearer token").body());
    }
    return credential;
  }

  private void push(HttpExchange exchange, String inboxId, ApiCredential credential)
      throws IOException {
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType != null && !contentType.toLowerCase().startsWith("application/json")) {
      sendJson(exchange, 415, new ApiError("UNSUPPORTED_MEDIA_TYPE", "需要 application/json").body());
      return;
    }
    byte[] body = readBody(exchange);
    if (body == null) {
      return;
    }
    if (body.length == 0) {
      sendJson(exchange, 400, new ApiError("INVALID_JSON", "请求体为空").body());
      return;
    }
    PushRequest request;
    try {
      request = InboxJson.MAPPER.readValue(body, PushRequest.class);
    } catch (Exception e) {
      sendJson(exchange, 400, new ApiError("INVALID_JSON", "请求体不是合法 JSON").body());
      return;
    }
    String channel = request.channel();
    if (channel == null || channel.isBlank()) {
      sendJson(exchange, 422, new ApiError("INVALID_ARGUMENT", "channel 不能为空").body());
      return;
    }
    if (!credential.allowsChannel(channel)) {
      sendJson(exchange, 403, new ApiError("FORBIDDEN", "token 无权写入该 channel").body());
      return;
    }
    String payload =
        request.payload() == null
            ? null
            : request.payload().isTextual()
                ? request.payload().asText()
                : InboxJson.MAPPER.writeValueAsString(request.payload());
    String kind = request.kind() == null || request.kind().isBlank() ? "notification" : request.kind();
    PushResult result =
        service.push(
            new PushCommand(
                inboxId,
                channel,
                kind,
                request.title(),
                payload,
                request.payloadRef(),
                request.dedupeKey(),
                request.priority(),
                null));
    sendJson(
        exchange,
        result.duplicate() ? 200 : 201,
        Map.of(
            "itemId", result.itemId(),
            "duplicate", result.duplicate(),
            "revision", result.revision(),
            "createdAt", result.createdAt().toString()));
  }

  private void listItems(HttpExchange exchange, String inboxId, ApiCredential credential)
      throws IOException {
    Set<String> channels = effectiveChannels(channels(exchange), credential);
    int limit = 50;
    String rawLimit = queryParam(exchange, "limit");
    if (rawLimit != null) {
      try {
        limit = Integer.parseInt(rawLimit);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("limit 必须是整数");
      }
    }
    List<ItemSummary> items = service.listAvailable(inboxId, channels, limit);
    sendJson(exchange, 200, new ItemListResponse(inboxId, items, items.size()));
  }

  private void claimItems(HttpExchange exchange, String inboxId, ApiCredential credential)
      throws IOException {
    ClaimRequest request = readJsonBody(exchange, ClaimRequest.class);
    if (request == null) {
      return;
    }
    long leaseSeconds = request.leaseSeconds() == null ? 300 : request.leaseSeconds();
    ClaimResult result =
        service.claim(
            inboxId,
            request.itemIds() == null ? List.of() : request.itemIds(),
            Duration.ofSeconds(leaseSeconds),
            claimChannelScope(credential));
    sendJson(exchange, 200, result);
  }

  private void routeClaimAction(HttpExchange exchange, String inboxId, String action)
      throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "POST");
      return;
    }
    ApiCredential credential = requireAuth(exchange);
    if (credential == null) {
      return;
    }
    if (!credential.allowsInbox(inboxId)) {
      sendJson(exchange, 403, new ApiError("FORBIDDEN", "token 无权访问该 inbox").body());
      return;
    }
    if (!service.inboxExists(inboxId)) {
      sendJson(exchange, 404, new ApiError("NOT_FOUND", "inbox 不存在").body());
      return;
    }
    if (!requiresScope(exchange, credential, ApiScopes.ITEMS_MANAGE)) {
      return;
    }
    switch (action) {
      case "renew" -> {
        RenewRequest request = readJsonBody(exchange, RenewRequest.class);
        if (request == null) {
          return;
        }
        long leaseSeconds = request.leaseSeconds() == null ? 300 : request.leaseSeconds();
        RenewResult result =
            service.renew(
                inboxId,
                request.claimTokens() == null ? List.of() : request.claimTokens(),
                Duration.ofSeconds(leaseSeconds));
        sendJson(exchange, 200, result);
      }
      case "complete" -> {
        CompleteRequest request = readJsonBody(exchange, CompleteRequest.class);
        if (request == null) {
          return;
        }
        List<Completion> completions = new java.util.ArrayList<>();
        if (request.completions() != null) {
          for (CompletionRequest item : request.completions()) {
            completions.add(
                new Completion(
                    item.claimToken(),
                    item.outcome() == null || item.outcome().isBlank()
                        ? CompletionOutcome.COMPLETED
                        : CompletionOutcome.fromWire(item.outcome()),
                    item.summary()));
          }
        }
        CompleteResult result = service.complete(inboxId, completions);
        sendJson(exchange, 200, result);
      }
      case "release" -> {
        ReleaseRequest request = readJsonBody(exchange, ReleaseRequest.class);
        if (request == null) {
          return;
        }
        ReleaseResult result =
            service.release(
                inboxId,
                request.claimTokens() == null ? List.of() : request.claimTokens());
        sendJson(exchange, 200, result);
      }
      default -> sendJson(exchange, 404, new ApiError("NOT_FOUND", "claim 操作不存在").body());
    }
  }

  private <T> T readJsonBody(HttpExchange exchange, Class<T> type) throws IOException {
    byte[] body = readBody(exchange);
    if (body == null) {
      return null;
    }
    if (body.length == 0) {
      sendJson(exchange, 400, new ApiError("INVALID_JSON", "请求体为空").body());
      return null;
    }
    try {
      return InboxJson.MAPPER.readValue(body, type);
    } catch (Exception e) {
      sendJson(exchange, 400, new ApiError("INVALID_JSON", "请求体不是合法 JSON").body());
      return null;
    }
  }

  private static Set<String> claimChannelScope(ApiCredential credential) {
    if (credential.channels().isEmpty() || credential.channels().contains("*")) {
      return Set.of();
    }
    return credential.channels();
  }

  private void status(HttpExchange exchange, String inboxId, ApiCredential credential)
      throws IOException {
    Set<String> channels = effectiveChannels(channels(exchange), credential);
    InboxStatus status = service.status(inboxId, channels);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("inbox", status.inboxId());
    body.put("revision", status.revision());
    body.put("available", status.available());
    body.put("claimed", status.claimed());
    body.put("channels", status.channels());
    body.put("updatedAt", status.updatedAt().toString());
    body.put("occupied", status.occupancy().occupied());
    body.put("occupiedBy", status.occupancy().occupiedBy());
    body.put(
        "occupiedAt",
        status.occupancy().occupiedAt() == null
            ? null
            : status.occupancy().occupiedAt().toString());
    sendJson(exchange, 200, body);
  }

  private void events(HttpExchange exchange, String inboxId, ApiCredential credential)
      throws IOException {
    String accept = exchange.getRequestHeaders().getFirst("Accept");
    if (accept != null
        && !accept.contains("text/event-stream")
        && !accept.contains("*/*")) {
      sendJson(exchange, 406, new ApiError("NOT_ACCEPTABLE", "需要 text/event-stream").body());
      return;
    }
    long cursor;
    String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
    if (lastEventId == null || lastEventId.isBlank()) {
      cursor = service.latestEventSeq(inboxId);
    } else {
      try {
        cursor = Long.parseLong(lastEventId.trim());
      } catch (NumberFormatException e) {
        sendJson(exchange, 400, new ApiError("INVALID_CURSOR", "Last-Event-ID 非法").body());
        return;
      }
      if (cursor < 0) {
        sendJson(exchange, 400, new ApiError("INVALID_CURSOR", "Last-Event-ID 不能为负").body());
        return;
      }
    }

    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
    exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream out = exchange.getResponseBody()) {
      while (!Thread.currentThread().isInterrupted()) {
        long generation = notifier.generation();
        List<StoredEvent> batch = service.eventsAfter(inboxId, cursor, EVENT_BATCH);
        if (!batch.isEmpty()) {
          for (StoredEvent event : batch) {
            StoredEvent visible = filterEvent(event, credential.channels());
            if (visible != null) {
              writeEvent(out, visible);
            }
            cursor = event.seq();
          }
          continue;
        }
        if (!notifier.awaitChange(generation, heartbeat)) {
          out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
          out.flush();
        }
      }
    } catch (IOException e) {
      LOG.debug("SSE 客户端断开 inbox={}", inboxId);
    } catch (RuntimeException e) {
      LOG.debug("SSE 流结束 inbox={} reason={}", inboxId, e.getMessage());
    }
  }

  private void writeEvent(OutputStream out, StoredEvent event) throws IOException {
    String json = InboxJson.MAPPER.writeValueAsString(CloudEvents.envelope(event));
    String frame =
        "id: " + event.seq() + "\n"
            + "event: inbox.changed\n"
            + "data: " + json + "\n\n";
    out.write(frame.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private ApiCredential authenticate(HttpExchange exchange) {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      return null;
    }
    String token = authorization.substring("Bearer ".length()).trim();
    return credentials.authenticate(token).orElse(null);
  }

  private boolean requiresScope(HttpExchange exchange, ApiCredential credential, String scope)
      throws IOException {
    if (!credential.hasScope(scope)) {
      sendJson(exchange, 403, new ApiError("FORBIDDEN", "token 缺少 scope: " + scope).body());
      return false;
    }
    return true;
  }

  private Set<String> channels(HttpExchange exchange) {
    String raw = exchange.getRequestURI().getRawQuery();
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String pair : raw.split("&")) {
      int equals = pair.indexOf('=');
      if (equals < 0 || !"channel".equals(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8))) {
        continue;
      }
      String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
      for (String part : value.split(",")) {
        if (!part.isBlank()) {
          out.add(part.trim());
        }
      }
    }
    return Set.copyOf(out);
  }

  private String queryParam(HttpExchange exchange, String name) {
    String raw = exchange.getRequestURI().getRawQuery();
    if (raw == null || raw.isBlank()) {
      return null;
    }
    for (String pair : raw.split("&")) {
      int equals = pair.indexOf('=');
      if (equals < 0) {
        continue;
      }
      String key = URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
      if (name.equals(key)) {
        return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private byte[] readBody(HttpExchange exchange) throws IOException {
    String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
    if (contentLength != null) {
      try {
        if (Long.parseLong(contentLength) > MAX_BODY) {
          sendJson(exchange, 413, new ApiError("PAYLOAD_TOO_LARGE", "请求体超过 1 MiB").body());
          return null;
        }
      } catch (NumberFormatException ignored) {
        // 读取时再受 MAX_BODY 约束。
      }
    }
    try (InputStream in = exchange.getRequestBody()) {
      byte[] bytes = in.readNBytes(MAX_BODY + 1);
      if (bytes.length > MAX_BODY) {
        sendJson(exchange, 413, new ApiError("PAYLOAD_TOO_LARGE", "请求体超过 1 MiB").body());
        return null;
      }
      return bytes;
    }
  }

  private static Set<String> effectiveChannels(Set<String> requested, ApiCredential credential) {
    if (credential.channels().isEmpty() || credential.channels().contains("*")) {
      return requested;
    }
    if (requested.isEmpty()) {
      return credential.channels();
    }
    LinkedHashSet<String> intersection = new LinkedHashSet<>(requested);
    intersection.retainAll(credential.channels());
    return Set.copyOf(intersection);
  }

  private static StoredEvent filterEvent(StoredEvent event, Set<String> allowed) {
    if (allowed.isEmpty() || allowed.contains("*")) {
      return event;
    }
    java.util.TreeMap<String, io.mosire.inbox.core.ChannelCounts> channels = new java.util.TreeMap<>();
    long available = 0;
    long claimed = 0;
    for (Map.Entry<String, io.mosire.inbox.core.ChannelCounts> entry : event.channels().entrySet()) {
      if (allowed.contains(entry.getKey())) {
        channels.put(entry.getKey(), entry.getValue());
        available += entry.getValue().available();
        claimed += entry.getValue().claimed();
      }
    }
    List<String> changed =
        event.changedChannels().stream().filter(allowed::contains).toList();
    if (changed.isEmpty()) {
      return null;
    }
    return new StoredEvent(
        event.seq(),
        event.eventId(),
        event.inboxId(),
        event.revision(),
        available,
        claimed,
        Map.copyOf(channels),
        changed,
        event.createdAt());
  }

  private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = InboxJson.MAPPER.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
    exchange.getResponseHeaders().set("Allow", allow);
    sendJson(exchange, 405, new ApiError("METHOD_NOT_ALLOWED", "方法不允许").body());
  }

  private static int statusFor(InboxException.Code code) {
    return switch (code) {
      case INVALID_ARGUMENT -> 422;
      case NOT_FOUND -> 404;
      case CONFLICT -> 409;
      case UNAVAILABLE -> 409;
      case CLAIM_INVALID, CLAIM_EXPIRED -> 409;
      case INTERNAL -> 500;
    };
  }

  private static List<String> segments(String path) {
    String normalized = path == null ? "" : path;
    if (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    if (normalized.endsWith("/") && normalized.length() > 1) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (normalized.isBlank()) {
      return List.of();
    }
    return Arrays.asList(normalized.split("/"));
  }

  @Override
  public synchronized void close() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  private void occupancy(HttpExchange exchange, String inboxId, ApiCredential credential)
      throws IOException {
    if ("GET".equals(exchange.getRequestMethod())) {
      if (!requiresScope(exchange, credential, ApiScopes.STATUS_READ)) {
        return;
      }
      sendJson(exchange, 200, service.occupancy(inboxId));
      return;
    }
    if ("PUT".equals(exchange.getRequestMethod())) {
      if (!requiresScope(exchange, credential, ApiScopes.INBOX_OCCUPY)) {
        return;
      }
      OccupancyRequest request = readJsonBody(exchange, OccupancyRequest.class);
      if (request == null) {
        return;
      }
      sendJson(exchange, 200, service.occupy(inboxId, request.occupiedBy()));
      return;
    }
    if ("DELETE".equals(exchange.getRequestMethod())) {
      if (!requiresScope(exchange, credential, ApiScopes.INBOX_OCCUPY)) {
        return;
      }
      sendJson(exchange, 200, service.vacate(inboxId));
      return;
    }
    methodNotAllowed(exchange, "GET, PUT, DELETE");
  }

  record OccupancyRequest(String occupiedBy) {}

  record ItemListResponse(String inbox, List<ItemSummary> items, int count) {}

  record ClaimRequest(List<String> itemIds, Long leaseSeconds) {}

  record RenewRequest(List<String> claimTokens, Long leaseSeconds) {}

  record CompletionRequest(String claimToken, String outcome, String summary) {}

  record CompleteRequest(List<CompletionRequest> completions) {}

  record ReleaseRequest(List<String> claimTokens) {}

  record CredentialIssueRequest(
      String label, List<String> scopes, List<String> channels, Long expiresInSeconds) {}

  record CredentialView(
      String id,
      String label,
      Set<String> scopes,
      String inbox,
      Set<String> channels,
      String fingerprint,
      String createdAt,
      String expiresAt,
      String lastUsedAt,
      String revokedAt,
      String rotatedFrom,
      String createdBy) {
    static CredentialView from(ApiCredential credential) {
      return new CredentialView(
          credential.id(),
          credential.label(),
          credential.scopes(),
          credential.inboxId(),
          credential.channels(),
          credential.fingerprint(),
          credential.createdAt() == null ? null : credential.createdAt().toString(),
          credential.expiresAt() == null ? null : credential.expiresAt().toString(),
          credential.lastUsedAt() == null ? null : credential.lastUsedAt().toString(),
          credential.revokedAt() == null ? null : credential.revokedAt().toString(),
          credential.rotatedFrom(),
          credential.createdBy());
    }
  }

  record CredentialIssuedResponse(CredentialView credential, String token) {}

  record PushRequest(
      String channel,
      String kind,
      String title,
      com.fasterxml.jackson.databind.JsonNode payload,
      String payloadRef,
      String dedupeKey,
      int priority) {}
}

package io.mosire.inbox.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 领域用例入口：做输入校验、在每次操作前清理过期 claim，并发出 SSE 观察信号。 */
public final class InboxService {
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final Pattern KIND = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final int MAX_TITLE = 512;
  private static final int MAX_PAYLOAD = 256 * 1024;
  private static final int MAX_REF = 2048;
  private static final int MAX_DEDUPE = 256;
  private static final int MAX_REQUEST_ITEMS = 50;
  private static final Duration MIN_LEASE = Duration.ofSeconds(30);
  private static final Duration MAX_LEASE = Duration.ofHours(1);

  private final InboxStore store;
  private final Clock clock;
  private final InboxChangeNotifier notifier;

  public InboxService(InboxStore store, Clock clock, InboxChangeNotifier notifier) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.notifier = Objects.requireNonNull(notifier, "notifier");
  }

  public PushResult push(PushCommand command) {
    Objects.requireNonNull(command, "command");
    requireId("inbox", command.inboxId());
    requirePattern("channel", command.channel(), ID);
    requirePattern("kind", command.kind(), KIND);
    requireText("summary", command.summary(), MAX_TITLE, true);
    requireText("content", command.content(), MAX_PAYLOAD, false);
    requireText("contentRef", command.contentRef(), MAX_REF, false);
    requireText("dedupeKey", command.dedupeKey(), MAX_DEDUPE, false);
    if (command.priority() < -1000 || command.priority() > 1000) {
      throw invalid("priority 必须在 -1000..1000");
    }
    Instant now = clock.instant();
    PushCommand normalized =
        new PushCommand(
            command.inboxId(),
            command.channel(),
            command.kind(),
            command.summary(),
            command.content(),
            command.contentRef(),
            command.dedupeKey(),
            command.priority(),
            command.availableAt() == null ? now : command.availableAt());
    PushResult result = store.push(normalized, now);
    if (!result.duplicate()) {
      notifier.changed();
    }
    return result;
  }

  public InboxStatus status(String inboxId, Set<String> channels) {
    requireId("inbox", inboxId);
    Set<String> normalized = normalizeChannels(channels);
    sweepExpired();
    return store.status(inboxId, normalized);
  }

  public InboxOccupancy occupancy(String inboxId) {
    requireId("inbox", inboxId);
    return store.occupancy(inboxId);
  }

  public InboxOccupancy occupy(String inboxId, String occupiedBy) {
    requireId("inbox", inboxId);
    requireText("occupiedBy", occupiedBy, 200, false);
    return store.occupy(inboxId, occupiedBy, clock.instant());
  }

  public InboxOccupancy vacate(String inboxId) {
    requireId("inbox", inboxId);
    return store.vacate(inboxId, clock.instant());
  }

  public void clearAllOccupancy() {
    store.clearAllOccupancy();
  }

  public List<ItemSummary> listAvailable(String inboxId, Set<String> channels, int limit) {
    requireId("inbox", inboxId);
    Set<String> normalized = normalizeChannels(channels);
    if (limit < 1 || limit > 100) {
      throw invalid("limit 必须在 1..100");
    }
    sweepExpired();
    return store.listAvailable(inboxId, normalized, limit, clock.instant());
  }

  public ItemDetail viewItem(String inboxId, String itemId) {
    return viewItem(inboxId, itemId, Set.of());
  }

  public ItemDetail viewItem(String inboxId, String itemId, Set<String> allowedChannels) {
    requireId("inbox", inboxId);
    requireItemId(itemId);
    return store.viewItem(inboxId, itemId, normalizeChannels(allowedChannels));
  }

  public ItemMutationResult updateItem(UpdateItemCommand command) {
    return updateItem(command, Set.of());
  }

  public ItemMutationResult updateItem(
      UpdateItemCommand command, Set<String> allowedChannels) {
    Objects.requireNonNull(command, "command");
    requireId("inbox", command.inboxId());
    requireItemId(command.itemId());
    boolean any =
        command.summary() != null
            || command.content() != null
            || command.contentRef() != null
            || command.kind() != null
            || command.priority() != null;
    if (!any) {
      throw invalid("update 至少需要 summary/content/contentRef/kind/priority 之一");
    }
    if (command.summary() != null) {
      requireText("summary", command.summary(), MAX_TITLE, true);
    }
    requireText("content", command.content(), MAX_PAYLOAD, false);
    requireText("contentRef", command.contentRef(), MAX_REF, false);
    if (command.kind() != null) {
      requirePattern("kind", command.kind(), KIND);
    }
    if (command.priority() != null) {
      requirePriority(command.priority());
    }
    requireExpectedRevision(command.expectedRevision());
    ItemMutationResult result =
        store.updateItem(command, clock.instant(), normalizeChannels(allowedChannels));
    notifier.changed();
    return result;
  }

  public ItemMutationResult appendItem(AppendItemCommand command) {
    return appendItem(command, Set.of());
  }

  public ItemMutationResult appendItem(
      AppendItemCommand command, Set<String> allowedChannels) {
    Objects.requireNonNull(command, "command");
    requireId("inbox", command.inboxId());
    requireItemId(command.itemId());
    requireText("content", command.content(), MAX_PAYLOAD, true);
    if (command.summary() != null) {
      requireText("summary", command.summary(), MAX_TITLE, true);
    }
    if (command.priority() != null) {
      requirePriority(command.priority());
    }
    requireExpectedRevision(command.expectedRevision());
    ItemMutationResult result =
        store.appendItem(command, clock.instant(), normalizeChannels(allowedChannels));
    notifier.changed();
    return result;
  }

  public ItemMutationResult deleteItem(
      String inboxId, String itemId, Long expectedRevision) {
    return deleteItem(inboxId, itemId, expectedRevision, Set.of());
  }

  public ItemMutationResult deleteItem(
      String inboxId,
      String itemId,
      Long expectedRevision,
      Set<String> allowedChannels) {
    requireId("inbox", inboxId);
    requireItemId(itemId);
    requireExpectedRevision(expectedRevision);
    ItemMutationResult result =
        store.deleteItem(
            inboxId,
            itemId,
            expectedRevision,
            clock.instant(),
            normalizeChannels(allowedChannels));
    notifier.changed();
    return result;
  }

  public ClaimResult claim(String inboxId, List<String> itemIds, Duration lease) {
    return claim(inboxId, itemIds, lease, Set.of());
  }

  public ClaimResult claim(
      String inboxId, List<String> itemIds, Duration lease, Set<String> allowedChannels) {
    requireId("inbox", inboxId);
    List<String> normalized = normalizeItemIds(itemIds);
    Set<String> normalizedChannels = normalizeChannels(allowedChannels);
    Duration safeLease = normalizeLease(lease);
    sweepExpired();
    ClaimResult result =
        store.claim(inboxId, normalized, safeLease, clock.instant(), normalizedChannels);
    if (!result.claimed().isEmpty()) {
      notifier.changed();
    }
    return result;
  }

  public RenewResult renew(String inboxId, List<String> claimTokens, Duration lease) {
    requireId("inbox", inboxId);
    List<String> tokens = normalizeTokens(claimTokens);
    Duration safeLease = normalizeLease(lease);
    return store.renew(inboxId, tokens, safeLease, clock.instant());
  }

  public CompleteResult complete(String inboxId, List<Completion> completions) {
    requireId("inbox", inboxId);
    if (completions == null || completions.isEmpty() || completions.size() > MAX_REQUEST_ITEMS) {
      throw invalid("completions 数量必须在 1.." + MAX_REQUEST_ITEMS);
    }
    List<Completion> normalized = new ArrayList<>(completions.size());
    for (Completion completion : completions) {
      if (completion == null) {
        throw invalid("completion 不能为 null");
      }
      requireText("claimToken", completion.claimToken(), 512, true);
      requireText("summary", completion.summary(), 4096, false);
      normalized.add(completion);
    }
    CompleteResult result = store.complete(inboxId, List.copyOf(normalized), clock.instant());
    if (result.completed().stream().anyMatch(ack -> !ack.idempotent())) {
      notifier.changed();
    }
    return result;
  }

  public ReleaseResult release(String inboxId, List<String> claimTokens) {
    requireId("inbox", inboxId);
    List<String> tokens = normalizeTokens(claimTokens);
    ReleaseResult result = store.release(inboxId, tokens, clock.instant());
    if (!result.releasedItemIds().isEmpty()) {
      notifier.changed();
    }
    return result;
  }

  public void sweepExpired() {
    int expired = store.expireClaims(clock.instant(), 1000);
    if (expired > 0) {
      notifier.changed();
    }
  }

  public boolean createInbox(String inboxId) {
    requireId("inbox", inboxId);
    return store.createInbox(inboxId);
  }

  public boolean inboxExists(String inboxId) {
    requireId("inbox", inboxId);
    return store.inboxExists(inboxId);
  }

  public List<StoredEvent> eventsAfter(String inboxId, long afterSeq, int limit) {
    requireId("inbox", inboxId);
    if (afterSeq < 0) {
      throw invalid("afterSeq 不能为负数");
    }
    if (limit < 1 || limit > 500) {
      throw invalid("limit 必须在 1..500");
    }
    return store.eventsAfter(inboxId, afterSeq, limit);
  }

  public long latestEventSeq(String inboxId) {
    requireId("inbox", inboxId);
    return store.latestEventSeq(inboxId);
  }

  private static void requireItemId(String itemId) {
    requireText("itemId", itemId, 512, true);
  }

  private static void requireExpectedRevision(Long expectedRevision) {
    if (expectedRevision != null && expectedRevision < 0) {
      throw invalid("expectedRevision 不能为负数");
    }
  }

  private static void requirePriority(int priority) {
    if (priority < -1000 || priority > 1000) {
      throw invalid("priority 必须在 -1000..1000");
    }
  }

  private static Duration normalizeLease(Duration lease) {
    if (lease == null) {
      return Duration.ofMinutes(5);
    }
    if (lease.compareTo(MIN_LEASE) < 0 || lease.compareTo(MAX_LEASE) > 0) {
      throw invalid("lease 必须在 30 秒到 1 小时之间");
    }
    return lease;
  }

  private static List<String> normalizeItemIds(List<String> itemIds) {
    if (itemIds == null || itemIds.isEmpty() || itemIds.size() > MAX_REQUEST_ITEMS) {
      throw invalid("itemIds 数量必须在 1.." + MAX_REQUEST_ITEMS);
    }
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String itemId : itemIds) {
      if (itemId == null || itemId.isBlank()) {
        throw invalid("itemId 不能为空");
      }
      unique.add(itemId);
    }
    return List.copyOf(unique);
  }

  private static List<String> normalizeTokens(List<String> tokens) {
    if (tokens == null || tokens.isEmpty() || tokens.size() > MAX_REQUEST_ITEMS) {
      throw invalid("claimTokens 数量必须在 1.." + MAX_REQUEST_ITEMS);
    }
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String token : tokens) {
      requireText("claimToken", token, 512, true);
      unique.add(token);
    }
    return List.copyOf(unique);
  }

  private static Set<String> normalizeChannels(Set<String> channels) {
    if (channels == null || channels.isEmpty()) {
      return Set.of();
    }
    if (channels.size() > 32) {
      throw invalid("channels 数量不能超过 32");
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String channel : channels) {
      requirePattern("channel", channel, ID);
      normalized.add(channel);
    }
    return Set.copyOf(normalized);
  }

  private static void requireId(String name, String value) {
    requirePattern(name, value, ID);
  }

  private static void requirePattern(String name, String value, Pattern pattern) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw invalid(name + " 只允许 1..64 位 [A-Za-z0-9._-]");
    }
  }

  private static void requireText(String name, String value, int max, boolean required) {
    if (required && (value == null || value.isBlank())) {
      throw invalid(name + " 不能为空");
    }
    if (value != null && value.length() > max) {
      throw invalid(name + " 超过长度上限 " + max);
    }
  }

  private static InboxException invalid(String message) {
    return new InboxException(InboxException.Code.INVALID_ARGUMENT, message);
  }
}

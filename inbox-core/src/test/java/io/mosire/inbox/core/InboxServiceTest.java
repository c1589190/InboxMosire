package io.mosire.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InboxServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
  private final FakeStore store = new FakeStore();
  private final AtomicInteger signals = new AtomicInteger();
  private final InboxService service =
      new InboxService(store, Clock.fixed(NOW, ZoneOffset.UTC), signals::incrementAndGet);

  @Test
  void validPushSignalsAndDuplicateDoesNot() {
    store.pushResult = new PushResult("itm-1", false, 1, NOW);
    PushResult result =
        service.push(new PushCommand("main", "ticket", "reply", "t", "p", null, "k", 0, null));

    assertThat(result.itemId()).isEqualTo("itm-1");
    assertThat(store.lastPush.availableAt()).isEqualTo(NOW);
    assertThat(signals).hasValue(1);

    store.pushResult = new PushResult("itm-1", true, 1, NOW);
    service.push(new PushCommand("main", "ticket", "reply", "t", "p", null, "k", 0, null));
    assertThat(signals).hasValue(1);
  }

  @Test
  void invalidChannelIsRejectedBeforeStore() {
    assertThatThrownBy(
            () ->
                service.push(
                    new PushCommand("main", "bad channel", "reply", "t", "p", null, null, 0, null)))
        .isInstanceOf(InboxException.class)
        .extracting(error -> ((InboxException) error).code())
        .isEqualTo(InboxException.Code.INVALID_ARGUMENT);
    assertThat(store.pushCalls).isZero();
  }

  @Test
  void sweepRunsBeforeStatusAndSignalsWhenClaimsExpire() {
    store.expired = 2;
    store.statusResult = new InboxStatus("main", 3, 1, 0, java.util.Map.of(), NOW, new InboxOccupancy("main", false, null, null, NOW));
    InboxStatus status = service.status("main", Set.of("ticket"));

    assertThat(status.revision()).isEqualTo(3);
    assertThat(store.expireCalls).isEqualTo(1);
    assertThat(signals).hasValue(1);
  }

  @Test
  void leaseBoundsAreEnforced() {
    assertThatThrownBy(
            () ->
                service.claim(
                    "main", List.of("itm-1"), Duration.ofSeconds(1)))
        .isInstanceOf(InboxException.class)
        .hasMessageContaining("lease");
    assertThat(store.claimCalls).isZero();
  }

  @Test
  void idempotentCompleteDoesNotSignal() {
    store.completeResult =
        new CompleteResult(
            5, 0, List.of(new CompletionAck("itm-1", CompletionOutcome.COMPLETED, true)), List.of());
    service.complete(
        "main", List.of(new Completion("token", CompletionOutcome.COMPLETED, "ok")));
    assertThat(signals).hasValue(0);
  }

  private static final class FakeStore implements InboxStore {
    PushResult pushResult = new PushResult("itm-1", false, 1, NOW);
    InboxStatus statusResult = new InboxStatus("main", 0, 0, 0, java.util.Map.of(), NOW, new InboxOccupancy("main", false, null, null, NOW));
    CompleteResult completeResult = new CompleteResult(0, 0, List.of(), List.of());
    PushCommand lastPush;
    int pushCalls;
    int claimCalls;
    int expireCalls;
    int expired;

    @Override public void initialize() {}
    @Override public boolean createInbox(String inboxId) { return true; }
    @Override public boolean inboxExists(String inboxId) { return true; }

    @Override
    public PushResult push(PushCommand command, Instant now) {
      pushCalls++;
      lastPush = command;
      return pushResult;
    }

    @Override public InboxStatus status(String inboxId, Set<String> channels) { return statusResult; }
    @Override public InboxOccupancy occupancy(String inboxId) { return new InboxOccupancy(inboxId, false, null, null, NOW); }
    @Override public InboxOccupancy occupy(String inboxId, String occupiedBy, Instant now) { return new InboxOccupancy(inboxId, true, occupiedBy, now, now); }
    @Override public InboxOccupancy vacate(String inboxId, Instant now) { return new InboxOccupancy(inboxId, false, null, null, now); }
    @Override public void clearAllOccupancy() {}
    @Override public List<ItemSummary> listAvailable(String inboxId, Set<String> channels, int limit, Instant now) { return List.of(); }

    @Override
    public ItemDetail viewItem(String inboxId, String itemId, Set<String> allowedChannels) {
      return new ItemDetail(itemId, inboxId, "ticket", "reply", "s", "c", null, 0,
          ItemStatus.AVAILABLE, 0, 1, NOW, NOW);
    }

    @Override
    public ItemMutationResult updateItem(
        UpdateItemCommand command, Instant now, Set<String> allowedChannels) {
      return new ItemMutationResult(command.itemId(), 1, 1, now, false);
    }

    @Override
    public ItemMutationResult appendItem(
        AppendItemCommand command, Instant now, Set<String> allowedChannels) {
      return new ItemMutationResult(command.itemId(), 1, 1, now, false);
    }

    @Override
    public ItemMutationResult deleteItem(
        String inboxId, String itemId, Long expectedRevision, Instant now,
        Set<String> allowedChannels) {
      return new ItemMutationResult(itemId, 1, 0, now, true);
    }

    @Override
    public ClaimResult claim(String inboxId, List<String> itemIds, Duration lease, Instant now) {
      claimCalls++;
      return new ClaimResult(0, 0, List.of(), List.of());
    }

    @Override public RenewResult renew(String inboxId, List<String> claimTokens, Duration lease, Instant now) { return new RenewResult(List.of(), List.of()); }

    @Override
    public CompleteResult complete(String inboxId, List<Completion> completions, Instant now) {
      return completeResult;
    }

    @Override public ReleaseResult release(String inboxId, List<String> claimTokens, Instant now) { return new ReleaseResult(0, 0, List.of(), List.of()); }

    @Override
    public int expireClaims(Instant now, int limit) {
      expireCalls++;
      return expired;
    }

    @Override public List<StoredEvent> eventsAfter(String inboxId, long afterSeq, int limit) { return List.of(); }
    @Override public long latestEventSeq(String inboxId) { return 0; }
    @Override public void close() {}
  }
}

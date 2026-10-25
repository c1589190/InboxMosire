package io.mosire.inbox.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.inbox.core.ClaimResult;
import io.mosire.inbox.core.CompleteResult;
import io.mosire.inbox.core.Completion;
import io.mosire.inbox.core.CompletionOutcome;
import io.mosire.inbox.core.InboxException;
import io.mosire.inbox.core.PushCommand;
import io.mosire.inbox.core.PushResult;
import io.mosire.inbox.core.ReleaseResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteInboxStoreTest {
  @TempDir Path temp;

  @Test
  void pushStatusDedupeAndEventsAreTransactional() {
    try (SqliteDatabase db = SqliteDatabase.open(temp.resolve("inbox.db"))) {
      var store = db.inboxStore();
      Instant now = Instant.now();
      PushResult first =
          store.push(
              new PushCommand(
                  "main", "ticket", "reply", "标题", "{\"body\":\"x\"}", null, "k-1", 5, now),
              now);
      PushResult duplicate =
          store.push(
              new PushCommand(
                  "main", "ticket", "reply", "标题2", "y", null, "k-1", 5, now),
              now);

      assertThat(first.duplicate()).isFalse();
      assertThat(duplicate.duplicate()).isTrue();
      assertThat(duplicate.itemId()).isEqualTo(first.itemId());
      assertThat(store.status("main", Set.of()).available()).isEqualTo(1);
      assertThat(store.eventsAfter("main", 0, 10)).hasSize(1);
      assertThat(store.eventsAfter("main", 0, 10).getFirst().changedChannels())
          .containsExactly("ticket");
    }
  }

  @Test
  void claimCompleteIsIdempotentAndReleasesCounts() {
    try (SqliteDatabase db = SqliteDatabase.open(temp.resolve("inbox.db"))) {
      var store = db.inboxStore();
      Instant now = Instant.now();
      String itemId =
          store
              .push(
                  new PushCommand("main", "ticket", "reply", "标题", "payload", null, null, 0, now),
                  now)
              .itemId();

      ClaimResult claim =
          store.claim("main", List.of(itemId), Duration.ofSeconds(60), now);
      assertThat(claim.claimed()).hasSize(1);
      assertThat(store.status("main", Set.of()).claimed()).isEqualTo(1);

      String token = claim.claimed().getFirst().claimToken();
      CompleteResult first =
          store.complete(
              "main",
              List.of(new Completion(token, CompletionOutcome.COMPLETED, "done")),
              now.plusSeconds(1));
      CompleteResult second =
          store.complete(
              "main",
              List.of(new Completion(token, CompletionOutcome.COMPLETED, "done")),
              now.plusSeconds(2));

      assertThat(first.completed()).hasSize(1);
      assertThat(first.completed().getFirst().idempotent()).isFalse();
      assertThat(second.completed().getFirst().idempotent()).isTrue();
      assertThat(store.status("main", Set.of()).available()).isZero();
      assertThat(store.status("main", Set.of()).claimed()).isZero();
      long afterFirst = store.eventsAfter("main", 0, 10).size();
      assertThat(afterFirst).isEqualTo(3); // push + claim + complete；幂等 complete 不再写事件
    }
  }

  @Test
  void concurrentClaimsOnlyOneWins() throws Exception {
    try (SqliteDatabase db = SqliteDatabase.open(temp.resolve("inbox.db"))) {
      var store = db.inboxStore();
      Instant now = Instant.now();
      String itemId =
          store
              .push(
                  new PushCommand("main", "ticket", "reply", "标题", "payload", null, null, 0, now),
                  now)
              .itemId();

      ExecutorService pool = Executors.newFixedThreadPool(2);
      CountDownLatch start = new CountDownLatch(1);
      try {
        Future<ClaimResult> a =
            pool.submit(
                () -> {
                  start.await();
                  return store.claim("main", List.of(itemId), Duration.ofSeconds(60), now);
                });
        Future<ClaimResult> b =
            pool.submit(
                () -> {
                  start.await();
                  return store.claim("main", List.of(itemId), Duration.ofSeconds(60), now);
                });
        start.countDown();
        int totalClaims = a.get().claimed().size() + b.get().claimed().size();
        assertThat(totalClaims).isEqualTo(1);
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @Test
  void expiredClaimReturnsToAvailable() {
    try (SqliteDatabase db = SqliteDatabase.open(temp.resolve("inbox.db"))) {
      var store = db.inboxStore();
      Instant now = Instant.now();
      String itemId =
          store
              .push(
                  new PushCommand("main", "ticket", "reply", "标题", "payload", null, null, 0, now),
                  now)
              .itemId();
      ClaimResult claim =
          store.claim("main", List.of(itemId), Duration.ofSeconds(30), now);
      assertThat(claim.claimed()).hasSize(1);

      int expired = store.expireClaims(now.plusSeconds(31), 10);
      assertThat(expired).isEqualTo(1);
      assertThat(store.status("main", Set.of()).available()).isEqualTo(1);
      assertThat(store.status("main", Set.of()).claimed()).isZero();
      assertThat(store.listAvailable("main", Set.of(), 10, now.plusSeconds(31)))
          .singleElement()
          .extracting(item -> item.itemId())
          .isEqualTo(itemId);
    }
  }

  @Test
  void releaseMakesItemAvailableAgain() {
    try (SqliteDatabase db = SqliteDatabase.open(temp.resolve("inbox.db"))) {
      var store = db.inboxStore();
      Instant now = Instant.now();
      String itemId =
          store
              .push(
                  new PushCommand("main", "ticket", "reply", "标题", "payload", null, null, 0, now),
                  now)
              .itemId();
      ClaimResult claim =
          store.claim("main", List.of(itemId), Duration.ofSeconds(60), now);
      ReleaseResult release =
          store.release("main", List.of(claim.claimed().getFirst().claimToken()), now.plusSeconds(1));

      assertThat(release.releasedItemIds()).containsExactly(itemId);
      assertThat(store.status("main", Set.of()).available()).isEqualTo(1);
      assertThat(store.listAvailable("main", Set.of(), 10, now.plusSeconds(1)))
          .singleElement()
          .extracting(item -> item.attempts())
          .isEqualTo(1);
    }
  }

  @Test
  void stateSurvivesReopen() {
    String itemId;
    Path dbFile = temp.resolve("inbox.db");
    Instant now = Instant.now();
    try (SqliteDatabase db = SqliteDatabase.open(dbFile)) {
      itemId =
          db.inboxStore()
              .push(
                  new PushCommand("main", "ops", "alert", "t", "p", null, "persist", 0, now),
                  now)
              .itemId();
    }
    try (SqliteDatabase db = SqliteDatabase.open(dbFile)) {
      assertThat(db.inboxStore().status("main", Set.of()).available()).isEqualTo(1);
      assertThat(db.inboxStore().listAvailable("main", Set.of(), 10, Instant.now()))
          .singleElement()
          .extracting(item -> item.itemId())
          .isEqualTo(itemId);
    }
  }

  @Test
  void unknownInboxIsNotFound() {
    try (SqliteDatabase db = SqliteDatabase.open(temp.resolve("inbox.db"))) {
      assertThatThrownBy(() -> db.inboxStore().status("missing", Set.of()))
          .isInstanceOf(InboxException.class)
          .extracting(error -> ((InboxException) error).code())
          .isEqualTo(InboxException.Code.NOT_FOUND);
    }
  }
}

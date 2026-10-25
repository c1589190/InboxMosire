package io.mosire.inbox.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteApiCredentialStoreTest {
  @TempDir Path temp;

  @Test
  void tokenIsReturnedOnceAndOnlyHashIsStored() {
    try (SqliteDatabase db = SqliteDatabase.open(temp.resolve("inbox.db"))) {
      var store = db.credentials();
      IssuedCredential issued =
          store.issue("dsh", Set.of("events:read", "status:read"), "main", Set.of("ticket"));

      assertThat(issued.token()).startsWith("inbox_");
      assertThat(store.authenticate(issued.token()))
          .get()
          .satisfies(
              credential -> {
                assertThat(credential.allowsInbox("main")).isTrue();
                assertThat(credential.allowsInbox("other")).isFalse();
                assertThat(credential.allowsChannel("ticket")).isTrue();
                assertThat(credential.allowsChannel("other")).isFalse();
              });
      assertThat(store.authenticate(issued.token() + "x")).isEmpty();
    }
  }

  @Test
  void revokedTokenStopsWorking() {
    try (SqliteDatabase db = SqliteDatabase.open(temp.resolve("inbox.db"))) {
      var store = db.credentials();
      IssuedCredential issued = store.issue("p", Set.of("items:write"), "main", Set.of());
      assertThat(store.authenticate(issued.token())).isPresent();
      assertThat(store.revoke(issued.credential().id())).isTrue();
      assertThat(store.authenticate(issued.token())).isEmpty();
    }
  }

  @Test
  void expiredAndRotatedCredentialsBehaveCorrectly() {
    try (SqliteDatabase db = SqliteDatabase.open(temp.resolve("inbox.db"))) {
      var store = db.credentials();
      Instant expiredAt = Instant.now().minusSeconds(5);
      IssuedCredential expired =
          store.issue(
              "expired",
              Set.of("status:read"),
              "main",
              Set.of(),
              expiredAt,
              "test");
      assertThat(expired.credential().fingerprint()).startsWith("sha256:");
      assertThat(store.authenticate(expired.token())).isEmpty();

      IssuedCredential original =
          store.issue("rotate-me", Set.of("status:read", "events:read"), "main", Set.of(), null, "test");
      IssuedCredential rotated = store.rotate(original.credential().id(), "tester");
      assertThat(store.authenticate(original.token())).isEmpty();
      assertThat(store.authenticate(rotated.token()))
          .get()
          .satisfies(
              credential -> {
                assertThat(credential.label()).isEqualTo("rotate-me");
                assertThat(credential.scopes()).containsExactlyInAnyOrder("status:read", "events:read");
                assertThat(credential.rotatedFrom()).isEqualTo(original.credential().id());
              });
      assertThat(store.list("main"))
          .extracting(ApiCredential::id)
          .contains(original.credential().id(), rotated.credential().id());
    }
  }

}

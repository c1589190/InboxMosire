package io.mosire.inbox.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
}

package io.mosire.inbox.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 事务边界所在端口。实现必须保证：任何改变 available/claimed 计数的操作都推进 revision 并写事件。
 *
 * <p>claim token 由实现生成并只返回这一次；数据库只保存其 hash。
 */
public interface InboxStore extends AutoCloseable {
  void initialize();

  void createInbox(String inboxId);

  boolean inboxExists(String inboxId);

  PushResult push(PushCommand command, Instant now);

  InboxStatus status(String inboxId, Set<String> channels);

  List<ItemSummary> listAvailable(
      String inboxId, Set<String> channels, int limit, Instant now);

  ClaimResult claim(String inboxId, List<String> itemIds, Duration lease, Instant now);

  RenewResult renew(String inboxId, List<String> claimTokens, Duration lease, Instant now);

  CompleteResult complete(String inboxId, List<Completion> completions, Instant now);

  ReleaseResult release(String inboxId, List<String> claimTokens, Instant now);

  int expireClaims(Instant now, int limit);

  List<StoredEvent> eventsAfter(String inboxId, long afterSeq, int limit);

  long latestEventSeq(String inboxId);

  @Override
  void close();
}

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

  /** @return true if created, false if it already existed. */
  boolean createInbox(String inboxId);

  boolean inboxExists(String inboxId);

  PushResult push(PushCommand command, Instant now);

  InboxStatus status(String inboxId, Set<String> channels);

  InboxOccupancy occupancy(String inboxId);

  InboxOccupancy occupy(String inboxId, String occupiedBy, Instant now);

  InboxOccupancy vacate(String inboxId, Instant now);

  /** 服务进程启动时清理上一个进程留下的占用标记；占用不是持久业务事实。 */
  void clearAllOccupancy();

  List<ItemSummary> listAvailable(
      String inboxId, Set<String> channels, int limit, Instant now);

  ClaimResult claim(String inboxId, List<String> itemIds, Duration lease, Instant now);

  /**
   * 领取时额外按调用方可见 channel 收窄；空集 = 不限。
   * 默认实现保持旧存储实现兼容。
   */
  default ClaimResult claim(
      String inboxId,
      List<String> itemIds,
      Duration lease,
      Instant now,
      Set<String> allowedChannels) {
    return claim(inboxId, itemIds, lease, now);
  }

  RenewResult renew(String inboxId, List<String> claimTokens, Duration lease, Instant now);

  CompleteResult complete(String inboxId, List<Completion> completions, Instant now);

  ReleaseResult release(String inboxId, List<String> claimTokens, Instant now);

  int expireClaims(Instant now, int limit);

  List<StoredEvent> eventsAfter(String inboxId, long afterSeq, int limit);

  long latestEventSeq(String inboxId);

  @Override
  void close();
}

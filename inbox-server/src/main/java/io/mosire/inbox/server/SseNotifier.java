package io.mosire.inbox.server;

import io.mosire.inbox.core.InboxChangeNotifier;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** 跨线程信号：DB event 是真相，本类只避免 SSE tailer 忙轮询。 */
public final class SseNotifier implements InboxChangeNotifier {
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = lock.newCondition();
  private long generation;

  @Override
  public void changed() {
    lock.lock();
    try {
      generation++;
      changed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  public long generation() {
    lock.lock();
    try {
      return generation;
    } finally {
      lock.unlock();
    }
  }

  /** @return true if state changed before timeout, false for heartbeat */
  public boolean awaitChange(long seenGeneration, Duration timeout) {
    long nanos = timeout.toNanos();
    lock.lock();
    try {
      while (generation == seenGeneration && nanos > 0) {
        nanos = changed.awaitNanos(nanos);
      }
      return generation != seenGeneration;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return true;
    } finally {
      lock.unlock();
    }
  }
}

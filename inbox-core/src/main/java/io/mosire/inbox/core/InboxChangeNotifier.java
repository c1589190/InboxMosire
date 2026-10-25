package io.mosire.inbox.core;

/** 进程内唤醒 SSE tailer 的轻量信号；不是业务事件，事件真相仍在 SQLite outbox。 */
@FunctionalInterface
public interface InboxChangeNotifier {
  InboxChangeNotifier NONE = () -> {};

  void changed();
}

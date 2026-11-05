package io.mosire.inbox.core;

/** 事件类型常量：outbox 中持久化，SSE 只改写传输名。 */
public final class InboxEventTypes {
  /** 旧的计数型事件，保持原样。 */
  public static final String CHANGED = "io.mosire.inbox.changed.v1";

  /** 新的推送事件：正文只在 data.text 中。 */
  public static final String PUSH = "io.mosire.inbox.push.v1";

  /** SSE `event:` 名称。 */
  public static final String SSE_CHANGED = "inbox.changed";

  public static final String SSE_PUSH = "inbox.push";

  private InboxEventTypes() {}
}

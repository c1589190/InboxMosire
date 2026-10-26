package io.mosire.inbox.core;

/** 追加 content 文本；summary/priority 可选更新。 */
public record AppendItemCommand(
    String inboxId,
    String itemId,
    String content,
    String summary,
    Integer priority,
    Long expectedRevision) {}

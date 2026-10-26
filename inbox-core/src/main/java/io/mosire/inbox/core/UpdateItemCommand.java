package io.mosire.inbox.core;

/** null 字段表示不修改；至少提供一个可修改字段。 */
public record UpdateItemCommand(
    String inboxId,
    String itemId,
    String summary,
    String content,
    String contentRef,
    String kind,
    Integer priority,
    Long expectedRevision) {}

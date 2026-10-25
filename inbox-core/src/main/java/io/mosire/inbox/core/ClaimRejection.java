package io.mosire.inbox.core;

/** 批量操作中的逐项拒绝；subject 是 itemId 或 claimToken，按操作类型而定。 */
public record ClaimRejection(String subject, String code, String message) {}

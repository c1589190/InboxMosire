package io.mosire.inbox.sqlite;

/** token 原文只在签发/轮换时返回一次。 */
public record IssuedCredential(ApiCredential credential, String token) {}

package io.mosire.inbox.sqlite;

/** token 原文只在签发时返回一次。 */
public record IssuedCredential(ApiCredential credential, String token) {}

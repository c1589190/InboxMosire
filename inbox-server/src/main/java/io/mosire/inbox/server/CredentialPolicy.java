package io.mosire.inbox.server;

import io.mosire.inbox.core.InboxException;
import io.mosire.inbox.sqlite.ApiCredential;
import java.util.Set;

/** Provisioning / credential delegation 的唯一策略点。 */
final class CredentialPolicy {
  private CredentialPolicy() {}

  static void requireEnsure(ApiCredential credential, String inboxId) {
    if (credential.hasScope(ApiScopes.ADMIN_INBOXES)) {
      return;
    }
    if (credential.hasScope(ApiScopes.INBOX_ENSURE)
        && inboxId.equals(credential.inboxId())
        && !"*".equals(credential.inboxId())) {
      return;
    }
    throw new SecurityException("token 无权 ensure inbox: " + inboxId);
  }

  static void requireCanIssueFor(ApiCredential issuer, String inboxId) {
    if (issuer.hasScope(ApiScopes.ADMIN_CREDENTIALS)) {
      return;
    }
    if (!issuer.hasScope(ApiScopes.CREDENTIALS_ISSUE_SELF)) {
      throw new SecurityException(
          "token 缺少 scope: " + ApiScopes.CREDENTIALS_ISSUE_SELF);
    }
    if ("*".equals(issuer.inboxId()) || !inboxId.equals(issuer.inboxId())) {
      throw new SecurityException(
          "self 模式只能为精确绑定的 inbox 签发 credential");
    }
  }

  static void validateIssuedScopes(
      ApiCredential issuer, Set<String> requestedScopes, Set<String> requestedChannels) {
    if (requestedScopes.isEmpty()) {
      throw new InboxException(InboxException.Code.INVALID_ARGUMENT, "scopes 不能为空");
    }
    for (String scope : requestedScopes) {
      if (!ApiScopes.ALL.contains(scope)) {
        throw new InboxException(
            InboxException.Code.INVALID_ARGUMENT, "未知 scope: " + scope);
      }
      if (!issuer.hasScope(ApiScopes.ADMIN_CREDENTIALS) && !issuer.scopes().contains(scope)) {
        throw new SecurityException(
            "不能签发超过自身的 scope: " + scope);
      }
    }
    if (issuer.hasScope(ApiScopes.ADMIN_CREDENTIALS)
        || issuer.channels().isEmpty()
        || issuer.channels().contains("*")) {
      return;
    }
    if (requestedChannels.isEmpty() || !issuer.channels().containsAll(requestedChannels)) {
      throw new SecurityException(
          "不能签发超过自身的 channel 范围");
    }
  }

  static void requireCanManage(ApiCredential issuer, ApiCredential target) {
    if (issuer.hasScope(ApiScopes.ADMIN_CREDENTIALS)) {
      return;
    }
    if (!issuer.hasScope(ApiScopes.CREDENTIALS_ISSUE_SELF)) {
      throw new SecurityException(
          "token 缺少 scope: " + ApiScopes.CREDENTIALS_ISSUE_SELF);
    }
    if ("*".equals(issuer.inboxId())
        || !issuer.inboxId().equals(target.inboxId())
        || !issuer.scopes().containsAll(target.scopes())) {
      throw new SecurityException(
          "不能管理超过自身权限的 credential");
    }
  }
}

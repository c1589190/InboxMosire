package io.mosire.inbox.server;

import java.util.Set;

public final class ApiScopes {
  public static final String ITEMS_WRITE = "items:write";
  public static final String ITEMS_READ = "items:read";
  public static final String ITEMS_MANAGE = "items:manage";
  public static final String EVENTS_READ = "events:read";
  public static final String STATUS_READ = "status:read";
  public static final String INBOX_ENSURE = "inbox:ensure";
  public static final String CREDENTIALS_ISSUE_SELF = "credentials:issue:self";
  public static final String ADMIN_INBOXES = "admin:inboxes";
  public static final String ADMIN_CREDENTIALS = "admin:credentials";

  public static final Set<String> ALL =
      Set.of(
          ITEMS_WRITE,
          ITEMS_READ,
          ITEMS_MANAGE,
          EVENTS_READ,
          STATUS_READ,
          INBOX_ENSURE,
          CREDENTIALS_ISSUE_SELF,
          ADMIN_INBOXES,
          ADMIN_CREDENTIALS);

  private ApiScopes() {}
}

package io.mosire.inbox.server;

import com.sun.net.httpserver.HttpExchange;
import io.mosire.inbox.sqlite.ApiCredential;

record RequestContext(HttpExchange exchange, String path, String inboxId, ApiCredential credential) {}

package io.mosire.inbox.server;

import io.mosire.agentlib.mcp.AgentToMcpServer;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.plugin.BuiltinToolSource;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.inbox.core.InboxService;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Agent 通用 MCP 面。MCP 只操作通知板，不参与任何宿主 session/followup。 */
public final class InboxMcpServer implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(InboxMcpServer.class);
  public static final String SERVER_NAME = "inbox-mosire";
  public static final String SERVER_VERSION = "0.1.0";

  private final String host;
  private final int port;
  private final String path;
  private final InboxService service;
  private final String defaultInbox;
  private AgentToMcpServer server;

  public InboxMcpServer(
      String host, int port, String path, InboxService service, String defaultInbox) {
    this.host = host;
    this.port = port;
    this.path = path;
    this.service = service;
    this.defaultInbox = defaultInbox;
  }

  public synchronized void start() throws IOException {
    if (server != null) {
      return;
    }
    if (!ServerConfig.isLoopback(host)) {
      throw new IllegalArgumentException("v1 MCP 面仅允许回环地址，拒绝绑定: " + host);
    }
    ToolRegistry registry = new ToolRegistry();
    List<AgentTool> tools = new InboxMcpTools(service, defaultInbox).all();
    BuiltinToolSource source = new BuiltinToolSource("inbox", tools);
    for (AgentTool tool : source.listTools()) {
      registry.register(source.id(), tool);
    }
    ToolContext caller =
        ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST));
    ToolCallAuthorizer authorizer = ToolCallAuthorizer.of(new ToolExecutionGuard());
    server =
        AgentToMcpServer.startHttp(
            host,
            port,
            path,
            registry,
            SERVER_NAME,
            SERVER_VERSION,
            caller,
            authorizer);
    LOG.info(
        "Inbox MCP 已启动: http://{}:{}{} tools={}",
        host,
        boundPort(),
        path,
        registry.list().stream().map(AgentTool::name).toList());
  }

  public int boundPort() {
    return server == null ? port : server.boundPort();
  }

  @Override
  public synchronized void close() {
    if (server != null) {
      server.close();
      server = null;
    }
  }
}

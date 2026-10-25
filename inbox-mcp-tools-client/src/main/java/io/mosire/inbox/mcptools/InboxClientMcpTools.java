package io.mosire.inbox.mcptools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.inbox.client.InboxClient;
import io.mosire.inbox.client.InboxClientException;
import io.mosire.inbox.client.InboxModels;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 面向“程序自己提供 MCP”的 inbox 工具集。
 *
 * <p>工具只通过 {@link InboxClient} 调 InboxMosire HTTP；raw token 只存在程序
 * 的 credential source 中，不作为工具参数或结果暴露。
 */
public final class InboxClientMcpTools {
  private static final Logger LOG = LoggerFactory.getLogger(InboxClientMcpTools.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final InboxClient client;
  private final String defaultInbox;

  public InboxClientMcpTools(InboxClient client, String defaultInbox) {
    this.client = client;
    this.defaultInbox = defaultInbox;
  }

  public List<AgentTool> all() {
    return List.of(
        statusTool(),
        listTool(),
        claimTool(),
        renewTool(),
        completeTool(),
        releaseTool(),
        pushTool(),
        credentialInfoTool());
  }

  private AgentTool statusTool() {
    return tool(
        "inbox_status",
        "Return inbox revision and available/claimed counts by channel. Contains no item content.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "channels", arrayProp("string", "Optional channel filter.")),
            List.of()),
        args -> {
          String inbox = inbox(args);
          return client.status(inbox, stringSet(args, "channels"));
        });
  }

  private AgentTool listTool() {
    return tool(
        "inbox_list",
        "List available inbox items. Returns ids, channels, kinds and safe titles only; call inbox_claim for payloads.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "channels", arrayProp("string", "Optional channel filter."),
                "limit", prop("integer", "1..100, default 50.")),
            List.of()),
        args -> {
          String inbox = inbox(args);
          return client.list(inbox, stringSet(args, "channels"), intValue(args, "limit", 50));
        });
  }

  private AgentTool claimTool() {
    return tool(
        "inbox_claim",
        "Atomically claim selected item ids. Returns claimToken per item; payload is returned only here.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "itemIds", arrayProp("string", "Selected item ids."),
                "leaseSeconds", prop("integer", "30..3600, default 300.")),
            List.of("itemIds")),
        args -> client.claim(inbox(args), stringList(args, "itemIds"), lease(args)));
  }

  private AgentTool renewTool() {
    return tool(
        "inbox_renew",
        "Extend active claim leases.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "claimTokens", arrayProp("string", "Claim tokens."),
                "leaseSeconds", prop("integer", "30..3600, default 300.")),
            List.of("claimTokens")),
        args ->
            client.renew(
                inbox(args), stringList(args, "claimTokens"), lease(args)));
  }

  private AgentTool completeTool() {
    return tool(
        "inbox_complete",
        "Complete or fail claimed items. Returns remaining available count.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "completions", completionArray()),
            List.of("completions")),
        args -> {
          Object raw = args.get("completions");
          if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("completions 必须是非空数组");
          }
          List<InboxModels.Completion> completions = new ArrayList<>();
          for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) {
              throw new IllegalArgumentException("completions 每项必须是对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) map;
            completions.add(
                new InboxModels.Completion(
                    requiredString(entry, "claimToken"),
                    optionalString(entry, "outcome"),
                    optionalString(entry, "summary")));
          }
          return client.complete(inbox(args), completions);
        });
  }

  private AgentTool releaseTool() {
    return tool(
        "inbox_release",
        "Release active claims back to available without completing.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "claimTokens", arrayProp("string", "Claim tokens.")),
            List.of("claimTokens")),
        args -> client.release(inbox(args), stringList(args, "claimTokens")));
  }

  private AgentTool pushTool() {
    return tool(
        "inbox_push",
        "Push a task or notification. Payload is never sent in SSE wakes.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "channel", prop("string", "Logical channel."),
                "kind", prop("string", "Item kind; defaults to notification."),
                "title", prop("string", "Short safe title."),
                "payload", Map.of("description", "Opaque payload."),
                "payloadRef", prop("string", "Optional external reference."),
                "dedupeKey", prop("string", "Deduplication key."),
                "priority", prop("integer", "-1000..1000.")),
            List.of("channel")),
        args -> {
          InboxModels.PushItem.Builder builder =
              InboxModels.PushItem.builder(requiredString(args, "channel"))
                  .kind(optionalString(args, "kind"))
                  .title(optionalString(args, "title"))
                  .payload(args.get("payload"))
                  .payloadRef(optionalString(args, "payloadRef"))
                  .dedupeKey(optionalString(args, "dedupeKey"))
                  .priority(intValue(args, "priority", 0));
          return client.push(inbox(args), builder.build());
        });
  }

  private AgentTool credentialInfoTool() {
    return tool(
        "inbox_credential_info",
        "Show metadata for the credential used by this program's inbox client. Never returns the raw token.",
        schema(Map.of(), List.of()),
        args -> client.whoAmI());
  }

  private AgentTool tool(
      String name, String description, Map<String, Object> schema, ToolExecutor executor) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return schema;
      }

      @Override
      public ToolResult execute(io.mosire.agentlib.tool.ToolContext context) {
        try {
          return ToolResult.ok(JSON.writeValueAsString(executor.execute(context.arguments())));
        } catch (InboxClientException e) {
          return ToolResult.error(e.code() == null ? "INBOX_CLIENT_ERROR" : e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
          return ToolResult.error("INVALID_ARGUMENT", e.getMessage());
        } catch (Exception e) {
          LOG.error("inbox MCP client tool 失败 tool={}", name, e);
          return ToolResult.error("INTERNAL", "内部错误");
        }
      }
    };
  }

  private String inbox(Map<String, Object> args) {
    String value = optionalString(args, "inbox");
    return value == null || value.isBlank() ? defaultInbox : value;
  }

  private static Duration lease(Map<String, Object> args) {
    return Duration.ofSeconds(intValue(args, "leaseSeconds", 300));
  }

  private static Map<String, Object> schema(
      Map<String, Object> properties, List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }
    schema.put("additionalProperties", false);
    return schema;
  }

  private static Map<String, Object> prop(String type, String description) {
    return Map.of("type", type, "description", description);
  }

  private static Map<String, Object> arrayProp(String itemType, String description) {
    return Map.of(
        "type", "array", "items", Map.of("type", itemType), "description", description);
  }

  private static Map<String, Object> completionArray() {
    return Map.of(
        "type",
        "array",
        "description",
        "One entry per claimed item.",
        "items",
        schema(
            Map.of(
                "claimToken", prop("string", "Claim token."),
                "outcome", prop("string", "completed or failed; default completed."),
                "summary", prop("string", "Optional audit summary.")),
            List.of("claimToken")));
  }

  private static List<String> stringList(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(key + " 必须是数组");
    }
    List<String> out = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException(key + " 必须只含非空字符串");
      }
      out.add(text);
    }
    return List.copyOf(out);
  }

  private static Set<String> stringSet(Map<String, Object> args, String key) {
    List<String> values = stringList(args, key);
    return values.isEmpty() ? Set.of() : Set.copyOf(values);
  }

  private static String requiredString(Map<String, Object> args, String key) {
    String value = optionalString(args, key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(key + " 不能为空");
    }
    return value;
  }

  private static String optionalString(Map<String, Object> args, String key) {
    Object value = args.get(key);
    return value == null ? null : value.toString();
  }

  private static int intValue(Map<String, Object> args, String key, int defaultValue) {
    Object value = args.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " 必须是整数");
    }
  }

  @FunctionalInterface
  private interface ToolExecutor {
    Object execute(Map<String, Object> arguments) throws Exception;
  }
}

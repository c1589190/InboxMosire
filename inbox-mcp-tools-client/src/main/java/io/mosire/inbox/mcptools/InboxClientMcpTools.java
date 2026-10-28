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
        restTool(),
        listTool(),
        viewTool(),
        updateTool(),
        appendTool(),
        deleteTool(),
        claimTool(),
        renewTool(),
        completeTool(),
        releaseTool(),
        pushTool(),
        occupancyTool(),
        occupyTool(),
        vacateTool(),
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

  private AgentTool restTool() {
    return tool(
        "inbox_rest",
        "Ask the caller's wake adapter to pause inbox followups for a bounded duration. This does not mutate inbox items; it is a session/binding hint.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "restSeconds",
                    prop(
                        "integer", "0..86400; 0 clears rest. The owning adapter applies it to this session.")),
            List.of("restSeconds")),
        args -> {
          String inbox = inbox(args);
          int seconds = intValue(args, "restSeconds", 0);
          if (seconds < 0 || seconds > 86400) {
            throw new IllegalArgumentException("restSeconds 必须在 0..86400");
          }
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("inbox", inbox);
          result.put("restSeconds", seconds);
          result.put("scope", "adapter-session");
          return result;
        });
  }

  private AgentTool listTool() {
    return tool(
        "inbox_list",
        "List available inbox items. Returns ids, channels, kinds and safe summaries only; call inbox_view for full content or inbox_claim to claim work.",
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

  private AgentTool viewTool() {
    return tool(
        "inbox_view",
        "Read one notification summary and full opaque content without claiming it.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "itemId", prop("string", "Notification id.")),
            List.of("itemId")),
        args -> client.view(inbox(args), requiredString(args, "itemId")));
  }

  private AgentTool updateTool() {
    return tool(
        "inbox_update",
        "Replace mutable notification fields. Only available items may be updated; claimed items return CONFLICT.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "itemId", prop("string", "Notification id."),
                "summary", prop("string", "New short summary."),
                "content", Map.of("description", "New opaque content."),
                "contentRef", prop("string", "Optional external content reference."),
                "kind", prop("string", "New kind."),
                "priority", prop("integer", "New priority -1000..1000."),
                "expectedRevision", prop("integer", "Optional optimistic revision guard.")),
            List.of("itemId")),
        args -> {
          Object content = args.get("content");
          return client.update(
              inbox(args),
              requiredString(args, "itemId"),
              new InboxModels.UpdateItem(
                  optionalString(args, "summary"),
                  contentValue(content),
                  optionalString(args, "contentRef"),
                  optionalString(args, "kind"),
                  args.get("priority") == null
                      ? null
                      : intValue(args, "priority", 0),
                  args.get("expectedRevision") == null
                      ? null
                      : longValue(args, "expectedRevision", 0)));
        });
  }

  private AgentTool appendTool() {
    return tool(
        "inbox_append",
        "Append opaque text content to an available notification, optionally updating summary/priority.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "itemId", prop("string", "Notification id."),
                "content", Map.of("description", "Content to append."),
                "summary", prop("string", "Optional new summary."),
                "priority", prop("integer", "Optional new priority."),
                "expectedRevision", prop("integer", "Optional optimistic revision guard.")),
            List.of("itemId", "content")),
        args -> client.append(
            inbox(args),
            requiredString(args, "itemId"),
            new InboxModels.AppendItem(
                contentValue(args.get("content")),
                optionalString(args, "summary"),
                args.get("priority") == null
                    ? null
                    : intValue(args, "priority", 0),
                args.get("expectedRevision") == null
                    ? null
                    : longValue(args, "expectedRevision", 0))));
  }

  private AgentTool deleteTool() {
    return tool(
        "inbox_delete",
        "Archive/delete an available notification. Claimed items return CONFLICT.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "itemId", prop("string", "Notification id."),
                "expectedRevision", prop("integer", "Optional optimistic revision guard."),
                "restSeconds",
                    prop(
                        "integer",
                        "Optional DSH-local wake rest after a successful archive; 0 clears rest.")),
            List.of("itemId")),
        args -> client.deleteItem(
            inbox(args),
            requiredString(args, "itemId"),
            args.get("expectedRevision") == null
                ? null
                : longValue(args, "expectedRevision", 0)));
  }

  private AgentTool claimTool() {
    return tool(
        "inbox_claim",
        "Atomically claim selected item ids. Returns claimToken and full opaque content per item.",
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
                "restSeconds",
                    prop(
                        "integer",
                        "Optional DSH-local wake rest after a successful completion; 0 clears rest."),
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
        "Push an inbox item with a short summary and opaque content. Content is not sent in wake summaries.",
        schema(
            pushProperties(),
            List.of("channel")),
        args -> {
          String summary = optionalString(args, "summary");
          if (summary == null) {
            summary = optionalString(args, "title");
          }
          Object content = args.get("content");
          if (content == null) {
            content = args.get("payload");
          }
          String contentRef = optionalString(args, "contentRef");
          if (contentRef == null) {
            contentRef = optionalString(args, "payloadRef");
          }
          InboxModels.PushItem.Builder builder =
              InboxModels.PushItem.builder(requiredString(args, "channel"))
                  .kind(optionalString(args, "kind"))
                  .summary(summary)
                  .content(contentValue(content))
                  .contentRef(contentRef)
                  .dedupeKey(optionalString(args, "dedupeKey"))
                  .priority(intValue(args, "priority", 0))
                  .source(optionalString(args, "source"));
          return client.push(inbox(args), builder.build());
        });
  }

  private AgentTool occupancyTool() {
    return tool(
        "inbox_occupancy",
        "Read the advisory inbox occupancy marker. Occupancy is not an access lock; token scope remains the access boundary.",
        schema(
            Map.of("inbox", prop("string", "Inbox id; defaults to the client default.")),
            List.of()),
        args -> client.occupancy(inbox(args)));
  }

  private AgentTool occupyTool() {
    return tool(
        "inbox_occupy",
        "Mark this inbox as occupied by the current conversation/host. Advisory only; it does not block other token holders.",
        schema(
            Map.of(
                "inbox", prop("string", "Inbox id; defaults to the client default."),
                "occupiedBy", prop("string", "Human/runtime label for the occupant.")),
            List.of("occupiedBy")),
        args ->
            client.occupy(
                inbox(args), requiredString(args, "occupiedBy")));
  }

  private AgentTool vacateTool() {
    return tool(
        "inbox_vacate",
        "Clear the advisory inbox occupancy marker.",
        schema(
            Map.of("inbox", prop("string", "Inbox id; defaults to the client default.")),
            List.of()),
        args -> client.vacate(inbox(args)));
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
          String code = e.code() == null ? "INBOX_CLIENT_ERROR" : e.code();
          return ToolResult.error(code, detailedMessage(e.getMessage(), e.details()));
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

  private static String detailedMessage(String message, Map<String, Object> details) {
    if (details == null || details.isEmpty()) {
      return message;
    }
    try {
      return message + " details=" + JSON.writeValueAsString(details);
    } catch (Exception ignored) {
      return message;
    }
  }

  private static Map<String, Object> pushProperties() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("inbox", prop("string", "Inbox id; defaults to the client default."));
    props.put("channel", prop("string", "Logical channel."));
    props.put("kind", prop("string", "Item kind; defaults to notification."));
    props.put("summary", prop("string", "Short summary shown in list/wake."));
    props.put("content", Map.of("description", "Opaque full content."));
    props.put("contentRef", prop("string", "Optional external content reference."));
    props.put("title", prop("string", "Deprecated alias for summary."));
    props.put("payload", Map.of("description", "Deprecated alias for content."));
    props.put("payloadRef", prop("string", "Deprecated alias for contentRef."));
    props.put("dedupeKey", prop("string", "Deduplication key."));
    props.put("priority", prop("integer", "-1000..1000."));
    props.put(
        "source",
        prop(
            "string",
            "Optional opaque provenance address (for example qq_group/bot-1/955432692); max 512 single-line chars."));
    return props;
  }

  private static String contentValue(Object value) {
    if (value == null) {
      return null;
    }
    return JSON.valueToTree(value).isTextual()
        ? JSON.valueToTree(value).asText()
        : JSON.valueToTree(value).toString();
  }

  private static long longValue(Map<String, Object> args, String key, long defaultValue) {
    Object value = args.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " 必须是整数");
    }
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

package io.mosire.inbox.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.inbox.core.AppendItemCommand;
import io.mosire.inbox.core.ClaimRejection;
import io.mosire.inbox.core.ClaimResult;
import io.mosire.inbox.core.ClaimedItem;
import io.mosire.inbox.core.CompleteResult;
import io.mosire.inbox.core.Completion;
import io.mosire.inbox.core.CompletionAck;
import io.mosire.inbox.core.CompletionOutcome;
import io.mosire.inbox.core.InboxException;
import io.mosire.inbox.core.InboxService;
import io.mosire.inbox.core.InboxStatus;
import io.mosire.inbox.core.ItemDetail;
import io.mosire.inbox.core.ItemMutationResult;
import io.mosire.inbox.core.ItemSummary;
import io.mosire.inbox.core.PushResult;
import io.mosire.inbox.core.ReleaseResult;
import io.mosire.inbox.core.RenewResult;
import io.mosire.inbox.core.RenewedClaim;
import io.mosire.inbox.core.UpdateItemCommand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 所有 MCP 工具。工具间不共享宿主 session 身份，claim token 是唯一处理能力凭证。 */
public final class InboxMcpTools {
  private static final Logger LOG = LoggerFactory.getLogger(InboxMcpTools.class);

  private final InboxService service;
  private final String defaultInbox;

  public InboxMcpTools(InboxService service, String defaultInbox) {
    this.service = service;
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
        pushTool());
  }

  private AgentTool statusTool() {
    return tool(
        "inbox_status",
        "Return inbox revision and available/claimed counts by channel. Contains no item content.",
        McpToolSupport.schema(
            Map.of(
                "inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."),
                "channels",
                    McpToolSupport.arrayProp("string", "Optional channel filter.")),
            List.of(),
            false),
        args -> {
          String inbox = McpToolSupport.inbox(args, defaultInbox);
          Set<String> channels = McpToolSupport.stringSet(args, "channels");
          InboxStatus status = service.status(inbox, channels);
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("inbox", status.inboxId());
          result.put("revision", status.revision());
          result.put("available", status.available());
          result.put("claimed", status.claimed());
          result.put("channels", status.channels());
          result.put("updatedAt", McpToolSupport.timestamp(status.updatedAt()));
          result.put("occupied", status.occupancy().occupied());
          result.put("occupiedBy", status.occupancy().occupiedBy());
          result.put(
              "occupiedAt", McpToolSupport.timestamp(status.occupancy().occupiedAt()));
          return result;
        });
  }

  private AgentTool restTool() {
    return tool(
        "inbox_rest",
        "Ask the caller's wake adapter to pause inbox followups for a bounded duration. This does not mutate inbox items; it is a session/binding hint.",
        McpToolSupport.schema(
            Map.of(
                "inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."),
                "restSeconds",
                    McpToolSupport.prop(
                        "integer", "0..86400; 0 clears rest. The owning adapter applies it to this session.")),
            List.of("restSeconds"),
            false),
        args -> {
          String inbox = McpToolSupport.inbox(args, defaultInbox);
          int seconds = McpToolSupport.intValue(args, "restSeconds", 0);
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
        "List available inbox items. Returns ids, channels, kinds and safe summaries only; call inbox_view for full content or inbox_claim to claim work. Agent chooses which items to process.",
        McpToolSupport.schema(
            Map.of(
                "inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."),
                "channels", McpToolSupport.arrayProp("string", "Optional channel filter."),
                "limit", McpToolSupport.prop("integer", "1..100, default 50.")),
            List.of(),
            false),
        args -> {
          String inbox = McpToolSupport.inbox(args, defaultInbox);
          Set<String> channels = McpToolSupport.stringSet(args, "channels");
          int limit = McpToolSupport.intValue(args, "limit", 50);
          List<ItemSummary> items = service.listAvailable(inbox, channels, limit);
          List<Map<String, Object>> serialized = new ArrayList<>();
          for (ItemSummary item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("itemId", item.itemId());
            entry.put("channel", item.channel());
            entry.put("kind", item.kind());
            entry.put("summary", item.summary() == null ? "" : item.summary());
            entry.put("title", item.summary() == null ? "" : item.summary());
            entry.put("priority", item.priority());
            entry.put("attempts", item.attempts());
            entry.put("createdAt", item.createdAt().toString());
            entry.put("source", item.source());
            serialized.add(entry);
          }
          return Map.of("inbox", inbox, "items", serialized, "count", serialized.size());
        });
  }

  private AgentTool viewTool() {
    return tool(
        "inbox_view",
        "Read one notification summary and full opaque content without claiming it. Content is not interpreted as a tool call.",
        McpToolSupport.schema(
            Map.of(
                "inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."),
                "itemId", McpToolSupport.prop("string", "Notification id.")),
            List.of("itemId"),
            false),
        args -> itemDetailResult(service.viewItem(
            McpToolSupport.inbox(args, defaultInbox),
            McpToolSupport.requiredString(args, "itemId"))));
  }

  private AgentTool updateTool() {
    return tool(
        "inbox_update",
        "Replace mutable notification fields. Only available items may be updated; claimed items return CONFLICT.",
        McpToolSupport.schema(
            Map.of(
                "inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."),
                "itemId", McpToolSupport.prop("string", "Notification id."),
                "summary", McpToolSupport.prop("string", "New short summary."),
                "content", Map.of("description", "New opaque content."),
                "contentRef", McpToolSupport.prop("string", "Optional external content reference."),
                "kind", McpToolSupport.prop("string", "New kind."),
                "priority", McpToolSupport.prop("integer", "New priority -1000..1000."),
                "expectedRevision", McpToolSupport.prop("integer", "Optional optimistic revision guard.")),
            List.of("itemId"),
            false),
        args -> {
          ItemMutationResult result =
              service.updateItem(
                  new UpdateItemCommand(
                      McpToolSupport.inbox(args, defaultInbox),
                      McpToolSupport.requiredString(args, "itemId"),
                      McpToolSupport.optionalString(args, "summary"),
                      McpToolSupport.contentValue(args.get("content")),
                      McpToolSupport.optionalString(args, "contentRef"),
                      McpToolSupport.optionalString(args, "kind"),
                      args.get("priority") == null
                          ? null
                          : McpToolSupport.intValue(args, "priority", 0),
                      args.get("expectedRevision") == null
                          ? null
                          : McpToolSupport.longValue(args, "expectedRevision", 0)));
          return mutationResult(result);
        });
  }

  private AgentTool appendTool() {
    return tool(
        "inbox_append",
        "Append opaque text content to an available notification, optionally update summary/priority. Only available items are mutable.",
        McpToolSupport.schema(
            Map.of(
                "inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."),
                "itemId", McpToolSupport.prop("string", "Notification id."),
                "content", Map.of("description", "Text/JSON content to append."),
                "summary", McpToolSupport.prop("string", "Optional new summary."),
                "priority", McpToolSupport.prop("integer", "Optional new priority."),
                "expectedRevision", McpToolSupport.prop("integer", "Optional optimistic revision guard.")),
            List.of("itemId", "content"),
            false),
        args -> {
          ItemMutationResult result =
              service.appendItem(
                  new AppendItemCommand(
                      McpToolSupport.inbox(args, defaultInbox),
                      McpToolSupport.requiredString(args, "itemId"),
                      McpToolSupport.contentValue(args.get("content")),
                      McpToolSupport.optionalString(args, "summary"),
                      args.get("priority") == null
                          ? null
                          : McpToolSupport.intValue(args, "priority", 0),
                      args.get("expectedRevision") == null
                          ? null
                          : McpToolSupport.longValue(args, "expectedRevision", 0)));
          return mutationResult(result);
        });
  }

  private AgentTool deleteTool() {
    return tool(
        "inbox_delete",
        "Archive/delete an available notification. Claimed items return CONFLICT; use complete for claimed work.",
        McpToolSupport.schema(
            Map.of(
                "inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."),
                "itemId", McpToolSupport.prop("string", "Notification id."),
                "expectedRevision", McpToolSupport.prop("integer", "Optional optimistic revision guard."),
                "restSeconds",
                    McpToolSupport.prop(
                        "integer",
                        "Optional DSH-local wake rest after a successful archive; 0 clears rest.")),
            List.of("itemId"),
            false),
        args -> mutationResult(service.deleteItem(
            McpToolSupport.inbox(args, defaultInbox),
            McpToolSupport.requiredString(args, "itemId"),
            args.get("expectedRevision") == null
                ? null
                : McpToolSupport.longValue(args, "expectedRevision", 0))));
  }

  private AgentTool claimTool() {
    return tool(
        "inbox_claim",
        "Atomically claim one or more selected item ids. Returns opaque claimToken per claimed item; payload is returned only here. Keep claimTokens for complete/release/renew.",
        McpToolSupport.schema(
            Map.of(
                "inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."),
                "itemIds", McpToolSupport.arrayProp("string", "Selected item ids."),
                "leaseSeconds", McpToolSupport.prop("integer", "30..3600, default 300.")),
            List.of("itemIds"),
            false),
        args -> {
          String inbox = McpToolSupport.inbox(args, defaultInbox);
          List<String> itemIds = McpToolSupport.stringList(args, "itemIds");
          ClaimResult result = service.claim(inbox, itemIds, McpToolSupport.lease(args));
          return claimResult(result);
        });
  }

  private AgentTool renewTool() {
    return tool(
        "inbox_renew",
        "Extend active claim leases. Renew only when processing legitimately takes longer.",
        McpToolSupport.schema(
            Map.of(
                "inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."),
                "claimTokens", McpToolSupport.arrayProp("string", "Claim tokens to renew."),
                "leaseSeconds", McpToolSupport.prop("integer", "30..3600, default 300.")),
            List.of("claimTokens"),
            false),
        args -> {
          String inbox = McpToolSupport.inbox(args, defaultInbox);
          RenewResult result =
              service.renew(
                  inbox,
                  McpToolSupport.stringList(args, "claimTokens"),
                  McpToolSupport.lease(args));
          List<Map<String, Object>> renewed = new ArrayList<>();
          for (RenewedClaim claim : result.renewed()) {
            renewed.add(
                Map.of(
                    "claimToken", claim.claimToken(),
                    "itemId", claim.itemId(),
                    "claimExpiresAt", claim.claimExpiresAt().toString()));
          }
          return Map.of("renewed", renewed, "rejected", rejections(result.rejected()));
        });
  }

  private AgentTool completeTool() {
    return tool(
        "inbox_complete",
        "Complete or fail claimed items. Returns remaining available count. If available > 0, either continue with another claim in this turn or end and let the host wake you.",
        McpToolSupport.schema(
            Map.of(
                "inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."),
                "restSeconds",
                    McpToolSupport.prop(
                        "integer",
                        "Optional DSH-local wake rest after a successful completion; 0 clears rest."),
                "completions",
                    Map.of(
                        "type", "array",
                        "description", "One entry per claimed item.",
                        "items",
                            McpToolSupport.schema(
                                Map.of(
                                    "claimToken", McpToolSupport.prop("string", "Claim token."),
                                    "outcome",
                                        McpToolSupport.prop(
                                            "string", "completed or failed; default completed."),
                                    "summary", McpToolSupport.prop("string", "Optional audit summary.")),
                                List.of("claimToken"),
                                false))),
            List.of("completions"),
            false),
        args -> {
          String inbox = McpToolSupport.inbox(args, defaultInbox);
          Object raw = args.get("completions");
          if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("completions 必须是非空数组");
          }
          List<Completion> completions = new ArrayList<>();
          for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) {
              throw new IllegalArgumentException("completions 每项必须是对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) map;
            String outcomeValue = McpToolSupport.optionalString(item, "outcome");
            CompletionOutcome outcome =
                outcomeValue == null || outcomeValue.isBlank()
                    ? CompletionOutcome.COMPLETED
                    : CompletionOutcome.fromWire(outcomeValue);
            completions.add(
                new Completion(
                    McpToolSupport.requiredString(item, "claimToken"),
                    outcome,
                    McpToolSupport.optionalString(item, "summary")));
          }
          CompleteResult result = service.complete(inbox, completions);
          return completeResult(result);
        });
  }

  private AgentTool releaseTool() {
    return tool(
        "inbox_release",
        "Release active claims back to available without completing. Use when this turn will not process them.",
        McpToolSupport.schema(
            Map.of(
                "inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."),
                "claimTokens", McpToolSupport.arrayProp("string", "Claim tokens to release.")),
            List.of("claimTokens"),
            false),
        args -> {
          String inbox = McpToolSupport.inbox(args, defaultInbox);
          ReleaseResult result =
              service.release(inbox, McpToolSupport.stringList(args, "claimTokens"));
          return (
              Map.of(
                  "revision", result.revision(),
                  "available", result.available(),
                  "releasedItemIds", result.releasedItemIds(),
                  "rejected", rejections(result.rejected())));
        });
  }

  private AgentTool pushTool() {
    return tool(
        "inbox_push",
        "Push a task or notification into the inbox. The agent may use this to schedule its own follow-up work.",
        McpToolSupport.schema(
            pushProperties(),
            List.of("channel"),
            false),
        args -> {
          PushResult result = McpToolSupport.push(service, args, defaultInbox);
          return (
              Map.of(
                  "itemId", result.itemId(),
                  "duplicate", result.duplicate(),
                  "revision", result.revision(),
                  "createdAt", result.createdAt().toString()));
        });
  }

  private Map<String, Object> claimResult(ClaimResult result) {
    List<Map<String, Object>> claimed = new ArrayList<>();
    for (ClaimedItem item : result.claimed()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("itemId", item.itemId());
      entry.put("channel", item.channel());
      entry.put("kind", item.kind());
      entry.put("summary", item.summary());
      entry.put("title", item.summary());
      entry.put("priority", item.priority());
      entry.put("claimToken", item.claimToken());
      entry.put("claimExpiresAt", item.claimExpiresAt().toString());
      entry.put("content", parseContent(item.content()));
      entry.put("source", item.source());
      if (item.contentRef() != null) {
        entry.put("contentRef", item.contentRef());
      }
      claimed.add(entry);
    }
    return Map.of(
        "revision", result.revision(),
        "available", result.available(),
        "claimed", claimed,
        "rejected", rejections(result.rejected()));
  }

  private Map<String, Object> completeResult(CompleteResult result) {
    List<Map<String, Object>> completed = new ArrayList<>();
    for (CompletionAck ack : result.completed()) {
      completed.add(
          Map.of(
              "itemId", ack.itemId(),
              "outcome", ack.outcome().wireValue(),
              "idempotent", ack.idempotent()));
    }
    return Map.of(
        "revision", result.revision(),
        "available", result.available(),
        "completed", completed,
        "rejected", rejections(result.rejected()));
  }

  private static List<Map<String, Object>> rejections(List<ClaimRejection> rejections) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (ClaimRejection rejection : rejections) {
      out.add(
          Map.of(
              "subject", rejection.subject(),
              "code", rejection.code(),
              "message", rejection.message()));
    }
    return out;
  }

  private static Map<String, Object> itemDetailResult(ItemDetail item) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("itemId", item.itemId());
    result.put("inbox", item.inboxId());
    result.put("channel", item.channel());
    result.put("kind", item.kind());
    result.put("summary", item.summary());
    result.put("title", item.summary());
    result.put("content", parseContent(item.content()));
    result.put("contentRef", item.contentRef());
    result.put("priority", item.priority());
    result.put("status", item.status().wireValue());
    result.put("attempts", item.attempts());
    result.put("revision", item.revision());
    result.put("createdAt", item.createdAt().toString());
    result.put("updatedAt", item.updatedAt().toString());
    result.put("source", item.source());
    return result;
  }

  private static Map<String, Object> mutationResult(ItemMutationResult result) {
    return Map.of(
        "itemId", result.itemId(),
        "revision", result.revision(),
        "available", result.available(),
        "updatedAt", result.updatedAt().toString(),
        "deleted", result.deleted());
  }

  private static Object parseContent(String content) {
    if (content == null || content.isBlank()) {
      return null;
    }
    try {
      JsonNode node = InboxJson.MAPPER.readTree(content);
      return node;
    } catch (Exception ignored) {
      return content;
    }
  }

  private static String detailedMessage(String message, Map<String, Object> details) {
    if (details == null || details.isEmpty()) {
      return message;
    }
    try {
      return message + " details=" + InboxJson.MAPPER.writeValueAsString(details);
    } catch (Exception ignored) {
      return message;
    }
  }

  private static Map<String, Object> pushProperties() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."));
    props.put("channel", McpToolSupport.prop("string", "Logical channel."));
    props.put("kind", McpToolSupport.prop("string", "Item kind; defaults to notification."));
    props.put("summary", McpToolSupport.prop("string", "Short summary shown in list/wake."));
    props.put("content", Map.of("description", "Opaque full content, returned only by view/claim."));
    props.put("contentRef", McpToolSupport.prop("string", "Optional external content reference."));
    props.put("title", McpToolSupport.prop("string", "Deprecated alias for summary."));
    props.put("payload", Map.of("description", "Deprecated alias for content."));
    props.put("payloadRef", McpToolSupport.prop("string", "Deprecated alias for contentRef."));
    props.put("dedupeKey", McpToolSupport.prop("string", "Deduplication key."));
    props.put("priority", McpToolSupport.prop("integer", "-1000..1000."));
    props.put(
        "source",
        McpToolSupport.prop(
            "string",
            "Optional opaque provenance address (for example qq_group/bot-1/955432692); max 512 single-line chars."));
    return props;
  }

  private AgentTool tool(
      String name,
      String description,
      Map<String, Object> schema,
      ToolExecutor executor) {
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
          Object result = executor.execute(context.arguments());
          return ToolResult.ok(InboxJson.MAPPER.writeValueAsString(result));
        } catch (InboxException e) {
          return ToolResult.error(e.code().name(), detailedMessage(e.getMessage(), e.details()));
        } catch (IllegalArgumentException e) {
          return ToolResult.error(
              InboxException.Code.INVALID_ARGUMENT.name(),
              e.getMessage() == null ? "参数无效" : e.getMessage());
        } catch (Exception e) {
          LOG.error("MCP 工具失败 tool={}", name, e);
          return ToolResult.error(InboxException.Code.INTERNAL.name(), "内部错误");
        }
      }
    };
  }

  @FunctionalInterface
  private interface ToolExecutor {
    Object execute(Map<String, Object> arguments) throws Exception;
  }
}

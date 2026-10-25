package io.mosire.inbox.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolResult;
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
import io.mosire.inbox.core.ItemSummary;
import io.mosire.inbox.core.PushResult;
import io.mosire.inbox.core.ReleaseResult;
import io.mosire.inbox.core.RenewResult;
import io.mosire.inbox.core.RenewedClaim;
import io.mosire.inbox.core.StoredEvent;
import java.time.Duration;
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
        listTool(),
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
          return (
              Map.of(
                  "inbox", status.inboxId(),
                  "revision", status.revision(),
                  "available", status.available(),
                  "claimed", status.claimed(),
                  "channels", status.channels(),
                  "updatedAt", McpToolSupport.timestamp(status.updatedAt())));
        });
  }

  private AgentTool listTool() {
    return tool(
        "inbox_list",
        "List available inbox items. Returns ids, channels, kinds and safe titles only; call inbox_claim to receive payloads. Agent chooses which items to process.",
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
            serialized.add(
                Map.of(
                    "itemId", item.itemId(),
                    "channel", item.channel(),
                    "kind", item.kind(),
                    "title", item.title() == null ? "" : item.title(),
                    "priority", item.priority(),
                    "attempts", item.attempts(),
                    "createdAt", item.createdAt().toString()));
          }
          return Map.of("inbox", inbox, "items", serialized, "count", serialized.size());
        });
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
            Map.of(
                "inbox", McpToolSupport.prop("string", "Inbox id; defaults to the server default."),
                "channel", McpToolSupport.prop("string", "Logical channel."),
                "kind", McpToolSupport.prop("string", "Item kind; defaults to notification."),
                "title", McpToolSupport.prop("string", "Short safe title."),
                "payload", Map.of("description", "Opaque item payload; never sent in SSE wakes."),
                "payloadRef", McpToolSupport.prop("string", "Optional external payload reference."),
                "dedupeKey", McpToolSupport.prop("string", "Deduplication key."),
                "priority", McpToolSupport.prop("integer", "-1000..1000.")),
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
      entry.put("title", item.title());
      entry.put("priority", item.priority());
      entry.put("claimToken", item.claimToken());
      entry.put("claimExpiresAt", item.claimExpiresAt().toString());
      entry.put("payload", parsePayload(item.payload()));
      if (item.payloadRef() != null) {
        entry.put("payloadRef", item.payloadRef());
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

  private static Object parsePayload(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      JsonNode node = InboxJson.MAPPER.readTree(payload);
      return node;
    } catch (Exception ignored) {
      return payload;
    }
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
          return ToolResult.error(e.code().name(), e.getMessage());
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

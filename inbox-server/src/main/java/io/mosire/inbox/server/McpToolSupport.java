package io.mosire.inbox.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.inbox.core.InboxException;
import io.mosire.inbox.core.InboxService;
import io.mosire.inbox.core.PushCommand;
import io.mosire.inbox.core.PushResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

final class McpToolSupport {
  private McpToolSupport() {}

  static String inbox(Map<String, Object> args, String defaultInbox) {
    Object value = args.get("inbox");
    return value == null || value.toString().isBlank() ? defaultInbox : value.toString();
  }

  static List<String> stringList(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(key + " 必须是字符串数组");
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

  static Set<String> stringSet(Map<String, Object> args, String key) {
    List<String> values = stringList(args, key);
    return values.isEmpty() ? Set.of() : Set.copyOf(new LinkedHashSet<>(values));
  }

  static String requiredString(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new IllegalArgumentException(key + " 不能为空");
    }
    return value.toString();
  }

  static String optionalString(Map<String, Object> args, String key) {
    Object value = args.get(key);
    return value == null ? null : value.toString();
  }

  static int intValue(Map<String, Object> args, String key, int defaultValue) {
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

  static long longValue(Map<String, Object> args, String key, long defaultValue) {
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

  static Duration lease(Map<String, Object> args) {
    return Duration.ofSeconds(intValue(args, "leaseSeconds", 300));
  }

  static PushResult push(InboxService service, Map<String, Object> args, String defaultInbox) {
    String inbox = inbox(args, defaultInbox);
    String summary = optionalString(args, "summary");
    if (summary == null) {
      summary = optionalString(args, "title");
    }
    JsonNode contentNode = InboxJson.MAPPER.valueToTree(args.get("content"));
    if (contentNode == null || contentNode.isNull()) {
      contentNode = InboxJson.MAPPER.valueToTree(args.get("payload"));
    }
    String content = null;
    if (contentNode != null && !contentNode.isNull()) {
      content = contentNode.isTextual() ? contentNode.asText() : contentNode.toString();
    }
    String contentRef = optionalString(args, "contentRef");
    if (contentRef == null) {
      contentRef = optionalString(args, "payloadRef");
    }
    return service.push(
        new PushCommand(
            inbox,
            requiredString(args, "channel"),
            optionalString(args, "kind") == null ? "notification" : optionalString(args, "kind"),
            summary,
            content,
            contentRef,
            optionalString(args, "dedupeKey"),
            intValue(args, "priority", 0),
            null));
  }

  static String contentValue(Object value) {
    JsonNode node = InboxJson.MAPPER.valueToTree(value);
    if (node == null || node.isNull()) {
      return null;
    }
    return node.isTextual() ? node.asText() : node.toString();
  }

  static Map<String, Object> schema(
      Map<String, Object> properties, List<String> required, boolean additionalProperties) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (required != null && !required.isEmpty()) {
      schema.put("required", required);
    }
    schema.put("additionalProperties", additionalProperties);
    return schema;
  }

  static Map<String, Object> prop(String type, String description) {
    return Map.of("type", type, "description", description);
  }

  static Map<String, Object> arrayProp(String itemType, String description) {
    Map<String, Object> item = Map.of("type", itemType);
    return Map.of("type", "array", "items", item, "description", description);
  }

  static String timestamp(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  static <T> T call(Supplier<T> supplier) {
    try {
      return supplier.get();
    } catch (InboxException e) {
      throw e;
    } catch (IllegalArgumentException e) {
      throw new InboxException(InboxException.Code.INVALID_ARGUMENT, e.getMessage(), e);
    }
  }
}

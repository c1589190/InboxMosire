package io.mosire.inbox.server;

import io.mosire.inbox.core.StoredEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把 outbox 事实转成 CloudEvents 1.0 JSON。只携带元数据。 */
final class CloudEvents {
  static final String TYPE = "io.mosire.inbox.changed.v1";

  private CloudEvents() {}

  static Map<String, Object> envelope(StoredEvent event) {
    Map<String, Object> channels = new LinkedHashMap<>();
    event
        .channels()
        .forEach(
            (name, counts) ->
                channels.put(
                    name,
                    Map.of("available", counts.available(), "claimed", counts.claimed())));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("inbox", event.inboxId());
    data.put("revision", event.revision());
    data.put("available", event.available());
    data.put("claimed", event.claimed());
    data.put("channels", channels);
    data.put("changedChannels", List.copyOf(event.changedChannels()));

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("specversion", "1.0");
    envelope.put("id", event.eventId());
    envelope.put("source", "urn:inboxmosire:inbox:" + event.inboxId());
    envelope.put("type", TYPE);
    envelope.put("subject", event.inboxId());
    envelope.put("time", event.createdAt().toString());
    envelope.put("datacontenttype", "application/json");
    envelope.put("data", data);
    return envelope;
  }
}

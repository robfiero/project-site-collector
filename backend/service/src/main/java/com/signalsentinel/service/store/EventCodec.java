package com.signalsentinel.service.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import com.signalsentinel.core.events.ContentChanged;
import com.signalsentinel.core.events.Event;
import com.signalsentinel.core.events.NewsUpdated;
import com.signalsentinel.core.events.SiteFetched;
import com.signalsentinel.core.events.WeatherUpdated;
import com.signalsentinel.core.util.JsonUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class EventCodec {
    private static final ObjectMapper MAPPER = JsonUtils.objectMapper();
    private static final Map<String, Class<? extends Event>> TYPES = Map.of(
            "CollectorTickStarted", CollectorTickStarted.class,
            "CollectorTickCompleted", CollectorTickCompleted.class,
            "SiteFetched", SiteFetched.class,
            "ContentChanged", ContentChanged.class,
            "NewsUpdated", NewsUpdated.class,
            "WeatherUpdated", WeatherUpdated.class,
            "AlertRaised", AlertRaised.class
    );

    private EventCodec() {
    }

    public static List<Class<? extends Event>> allEventTypes() {
        return List.copyOf(TYPES.values());
    }

    public static String toJsonLine(Event event) {
        try {
            return MAPPER.writeValueAsString(new StoredEvent(event.type(), event.timestamp(), event));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize event", e);
        }
    }

    public static Event fromJsonLine(String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            String type = node.path("type").asText();
            Class<? extends Event> eventClass = TYPES.get(type);
            if (eventClass == null) {
                throw new IllegalArgumentException("Unsupported event type: " + type);
            }
            return MAPPER.treeToValue(node.path("event"), eventClass);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to deserialize event", e);
        }
    }

    public static String toSseData(Event event) {
        try {
            return MAPPER.writeValueAsString(new StoredEvent(event.type(), event.timestamp(), event));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize SSE event", e);
        }
    }

    public static void subscribeAll(com.signalsentinel.core.bus.EventBus bus, Consumer<Event> consumer) {
        bus.subscribe(CollectorTickStarted.class, consumer::accept);
        bus.subscribe(CollectorTickCompleted.class, consumer::accept);
        bus.subscribe(SiteFetched.class, consumer::accept);
        bus.subscribe(ContentChanged.class, consumer::accept);
        bus.subscribe(NewsUpdated.class, consumer::accept);
        bus.subscribe(WeatherUpdated.class, consumer::accept);
        bus.subscribe(AlertRaised.class, consumer::accept);
    }

    private record StoredEvent(String type, Instant timestamp, Event event) {
    }
}

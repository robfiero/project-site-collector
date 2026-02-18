package com.signalsentinel.service.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import com.signalsentinel.core.events.ContentChanged;
import com.signalsentinel.core.events.EnvAqiUpdated;
import com.signalsentinel.core.events.EnvWeatherUpdated;
import com.signalsentinel.core.events.Event;
import com.signalsentinel.core.events.LoginFailed;
import com.signalsentinel.core.events.LoginSucceeded;
import com.signalsentinel.core.events.NewsUpdated;
import com.signalsentinel.core.events.PasswordResetFailed;
import com.signalsentinel.core.events.PasswordResetRequested;
import com.signalsentinel.core.events.PasswordResetSucceeded;
import com.signalsentinel.core.events.SiteFetched;
import com.signalsentinel.core.events.UserRegistered;
import com.signalsentinel.core.events.WeatherUpdated;
import com.signalsentinel.core.util.JsonUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class EventCodec {
    private static final ObjectMapper MAPPER = JsonUtils.objectMapper();
    private static final Map<String, Class<? extends Event>> TYPES = Map.ofEntries(
            Map.entry("CollectorTickStarted", CollectorTickStarted.class),
            Map.entry("CollectorTickCompleted", CollectorTickCompleted.class),
            Map.entry("SiteFetched", SiteFetched.class),
            Map.entry("ContentChanged", ContentChanged.class),
            Map.entry("NewsUpdated", NewsUpdated.class),
            Map.entry("WeatherUpdated", WeatherUpdated.class),
            Map.entry("EnvWeatherUpdated", EnvWeatherUpdated.class),
            Map.entry("EnvAqiUpdated", EnvAqiUpdated.class),
            Map.entry("AlertRaised", AlertRaised.class),
            Map.entry("UserRegistered", UserRegistered.class),
            Map.entry("LoginSucceeded", LoginSucceeded.class),
            Map.entry("LoginFailed", LoginFailed.class),
            Map.entry("PasswordResetRequested", PasswordResetRequested.class),
            Map.entry("PasswordResetSucceeded", PasswordResetSucceeded.class),
            Map.entry("PasswordResetFailed", PasswordResetFailed.class)
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
        bus.subscribe(EnvWeatherUpdated.class, consumer::accept);
        bus.subscribe(EnvAqiUpdated.class, consumer::accept);
        bus.subscribe(AlertRaised.class, consumer::accept);
        bus.subscribe(UserRegistered.class, consumer::accept);
        bus.subscribe(LoginSucceeded.class, consumer::accept);
        bus.subscribe(LoginFailed.class, consumer::accept);
        bus.subscribe(PasswordResetRequested.class, consumer::accept);
        bus.subscribe(PasswordResetSucceeded.class, consumer::accept);
        bus.subscribe(PasswordResetFailed.class, consumer::accept);
    }

    private record StoredEvent(String type, Instant timestamp, Event event) {
    }
}

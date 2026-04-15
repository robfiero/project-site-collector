package com.signalsentinel.service.store;

import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.EnvAqiUpdated;
import com.signalsentinel.core.events.EnvWeatherUpdated;
import com.signalsentinel.core.events.Event;
import com.signalsentinel.core.events.LoginFailed;
import com.signalsentinel.core.events.LoginSucceeded;
import com.signalsentinel.core.events.PasswordResetFailed;
import com.signalsentinel.core.events.PasswordResetRequested;
import com.signalsentinel.core.events.PasswordResetSucceeded;
import com.signalsentinel.core.events.UserRegistered;
import com.signalsentinel.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventCodecTest {
    @Test
    void serializesAndDeserializesEventLines() {
        Event event = new AlertRaised(
                Instant.parse("2026-02-12T20:00:00Z"),
                "collector",
                "hello",
                Map.of("k", "v")
        );

        String line = EventCodec.toJsonLine(event);
        Event parsed = EventCodec.fromJsonLine(line);
        String sseData = EventCodec.toSseData(event);

        assertEquals("AlertRaised", parsed.type());
        assertTrue(line.contains("\"type\":\"AlertRaised\""));
        assertTrue(sseData.contains("\"type\":\"AlertRaised\""));
        assertEquals(17, EventCodec.allEventTypes().size());
    }

    @Test
    void rejectsUnsupportedOrInvalidPayload() {
        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class, () ->
                EventCodec.fromJsonLine("{\"type\":\"Nope\",\"timestamp\":\"2026-02-12T20:00:00Z\",\"event\":{}}")
        );
        assertTrue(unsupported.getMessage().contains("Unsupported event type"));

        IllegalStateException invalid = assertThrows(IllegalStateException.class, () ->
                EventCodec.fromJsonLine("not-json")
        );
        assertTrue(invalid.getMessage().contains("Unable to deserialize event"));
    }

    @Test
    void roundTripsEnrichedEnvironmentEvents() {
        Event weather = new EnvWeatherUpdated(
                Instant.parse("2026-02-18T20:00:00Z"),
                "02108",
                "Boston, MA (02108)",
                42.35,
                -71.06,
                68.5,
                "Partly Cloudy",
                "NOAA",
                1771464000000L,
                "OK",
                null,
                "https://api.weather.gov/mock",
                "2026-02-18T19:00:00Z"
        );
        Event aqi = new EnvAqiUpdated(
                Instant.parse("2026-02-18T20:00:00Z"),
                "02108",
                "Boston, MA (02108)",
                42.35,
                -71.06,
                42,
                "Good",
                null,
                "AirNow",
                1771464000000L,
                "OK",
                null,
                "https://www.airnowapi.org/mock",
                "2026-02-18 19:00"
        );

        Event parsedWeather = EventCodec.fromJsonLine(EventCodec.toJsonLine(weather));
        Event parsedAqi = EventCodec.fromJsonLine(EventCodec.toJsonLine(aqi));

        assertEquals("EnvWeatherUpdated", parsedWeather.type());
        assertEquals("EnvAqiUpdated", parsedAqi.type());
    }

    @Test
    void maskEmailObscuresMiddleOfLocalPart() {
        assertEquals("s***a@example.com", EventCodec.maskEmail("shiela@example.com"));
        assertEquals("t***y@acme.com",    EventCodec.maskEmail("tony@acme.com"));
        assertEquals("a***@x.com",        EventCodec.maskEmail("a@x.com"));
        assertEquals("a***b@x.com",        EventCodec.maskEmail("abb@x.com"));
        assertEquals("***",               EventCodec.maskEmail(null));
        assertEquals("***",               EventCodec.maskEmail("notanemail"));
    }

    @Test
    void authEventsHaveEmailMaskedInJsonLineAndSseData() {
        Instant t = Instant.parse("2026-02-12T20:00:00Z");
        String email = "shiela@example.com";
        String masked = "s***a@example.com";

        assertEmailMasked(new UserRegistered(t, "uid-1", email), masked);
        assertEmailMasked(new LoginSucceeded(t, "uid-1", email), masked);
        assertEmailMasked(new LoginFailed(t, email, "bad password"), masked);
        assertEmailMasked(new PasswordResetRequested(t, email), masked);
        assertEmailMasked(new PasswordResetSucceeded(t, "uid-1", email), masked);
        assertEmailMasked(new PasswordResetFailed(t, email, "expired"), masked);
    }

    @Test
    void nonAuthEventsAreNotAlteredBySanitize() {
        Event event = new AlertRaised(
                Instant.parse("2026-02-12T20:00:00Z"),
                "collector",
                "something went wrong",
                Map.of("k", "v")
        );
        String line = EventCodec.toJsonLine(event);
        assertTrue(line.contains("something went wrong"));
        assertFalse(line.contains("***"));
    }

    private void assertEmailMasked(Event event, String masked) {
        String line = EventCodec.toJsonLine(event);
        String sse = EventCodec.toSseData(event);
        assertTrue(line.contains(masked), "expected masked email in toJsonLine for " + event.type());
        assertTrue(sse.contains(masked),  "expected masked email in toSseData for " + event.type());
        assertFalse(line.contains("shiela@example.com"), "raw email must not appear in toJsonLine for " + event.type());
        assertFalse(sse.contains("shiela@example.com"),  "raw email must not appear in toSseData for " + event.type());
    }

    @Test
    void serializedEnvelopeContainsCanonicalTimestampEpochMillisNearNow() throws Exception {
        Event event = new AlertRaised(Instant.now(), "collector", "probe", Map.of());

        long before = Instant.now().toEpochMilli();
        String sse = EventCodec.toSseData(event);
        long after = Instant.now().toEpochMilli();

        var node = JsonUtils.objectMapper().readTree(sse);
        long timestampEpochMillis = node.path("timestampEpochMillis").asLong(-1);
        long legacyTimestamp = node.path("timestamp").asLong(-1);

        assertTrue(timestampEpochMillis > 1_700_000_000_000L);
        assertTrue(timestampEpochMillis >= before - 5_000 && timestampEpochMillis <= after + 5_000);
        assertEquals(timestampEpochMillis, legacyTimestamp);
    }
}

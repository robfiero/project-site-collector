package com.signalsentinel.service.store;

import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.EnvAqiUpdated;
import com.signalsentinel.core.events.EnvWeatherUpdated;
import com.signalsentinel.core.events.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(15, EventCodec.allEventTypes().size());
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
}

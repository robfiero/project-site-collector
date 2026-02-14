package com.signalsentinel.service.store;

import com.signalsentinel.core.events.AlertRaised;
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
        assertEquals(7, EventCodec.allEventTypes().size());
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
}

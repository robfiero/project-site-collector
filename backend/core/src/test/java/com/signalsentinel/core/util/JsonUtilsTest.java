package com.signalsentinel.core.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilsTest {
    @Test
    void objectMapperIsSingletonAndConfigured() throws Exception {
        ObjectMapper first = JsonUtils.objectMapper();
        ObjectMapper second = JsonUtils.objectMapper();

        assertSame(first, second);
        assertFalse(first.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        assertTrue(first.writeValueAsString(new Payload("ok", null, Instant.parse("2026-02-01T00:00:00Z")))
                .contains("\"name\":\"ok\""));
    }

    @Test
    void objectMapperHandlesJavaTimeAndIgnoresUnknownProperties() throws Exception {
        ObjectMapper mapper = JsonUtils.objectMapper();
        Payload payload = new Payload("ok", null, Instant.parse("2026-02-01T00:00:00Z"));

        String json = mapper.writeValueAsString(payload);
        var tree = mapper.readTree(json);
        assertEquals("ok", tree.get("name").asText());
        assertTrue(tree.has("createdAt"));
        assertFalse(tree.has("optional"));

        Payload parsed = mapper.readValue(
                "{\"name\":\"ok\",\"createdAt\":\"2026-02-01T00:00:00Z\",\"unknown\":1}",
                Payload.class
        );
        assertEquals(payload.name(), parsed.name());
        assertEquals(payload.createdAt(), parsed.createdAt());
    }

    private record Payload(String name, String optional, Instant createdAt) {
    }
}

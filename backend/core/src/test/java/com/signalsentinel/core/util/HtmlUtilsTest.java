package com.signalsentinel.core.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlUtilsTest {
    @Test
    void titleExtractionHandlesEdgeCases() {
        assertTrue(HtmlUtils.extractTitle("<html><body>No title</body></html>").isEmpty());
        assertEquals("UPPER", HtmlUtils.extractTitle("<html><head><TITLE>UPPER</TITLE></head></html>").orElseThrow());
        assertEquals(
                "Hello World",
                HtmlUtils.extractTitle("<html><head><title>\n  Hello   World \n</title></head></html>").orElseThrow()
        );
        assertTrue(HtmlUtils.extractTitle("<html><head><title>Broken").isEmpty());
    }

    @Test
    void linkExtractionHandlesRelativeAndUnsafeSchemes() {
        String html = """
                <html><body>
                  <a href=\"/foo\">rel</a>
                  <a href=\"https://example.com/x\">abs</a>
                  <a href=\"mailto:test@example.com\">mail</a>
                  <a href=\"javascript:void(0)\">js</a>
                  <a href=\"/foo\">dup</a>
                </body></html>
                """;

        List<String> links = HtmlUtils.extractLinks(html);

        assertEquals(List.of("/foo", "https://example.com/x", "/foo"), links);
    }
}

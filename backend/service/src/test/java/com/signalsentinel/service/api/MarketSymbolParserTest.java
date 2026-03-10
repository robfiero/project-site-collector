package com.signalsentinel.service.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketSymbolParserTest {
    @Test
    void parseAcceptsCaretSymbols() {
        List<String> values = MarketSymbolParser.parse("aapl,^gspc, ^ndx ");
        assertEquals(List.of("AAPL", "^GSPC", "^NDX"), values);
    }

    @Test
    void parseRejectsInvalidSymbols() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> MarketSymbolParser.parse("AAPL,$BAD"));
        assertEquals("Invalid symbol: $BAD", error.getMessage());
    }
}

package com.signalsentinel.service.tls;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsCheckMainTest {
    @Test
    void missingArgsPrintsUsageAndReturnsNonZero() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = TlsCheckMain.run(new String[0], stream(out), stream(err));

        assertEquals(1, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Usage: TlsCheckMain <https-url>"));
    }

    @Test
    void invalidUrlIsHandledGracefully() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = TlsCheckMain.run(new String[]{"http://example.com"}, stream(out), stream(err));

        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Only https URLs are supported"));
    }

    private static PrintStream stream(ByteArrayOutputStream out) {
        return new PrintStream(out, true, StandardCharsets.UTF_8);
    }
}

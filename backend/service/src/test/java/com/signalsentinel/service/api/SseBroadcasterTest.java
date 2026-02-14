package com.signalsentinel.service.api;

import com.signalsentinel.core.bus.EventBus;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseBroadcasterTest {
    @Test
    void handleReturnsPromptlyWhenThreadAlreadyInterrupted() throws Exception {
        EventBus eventBus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected handler error", error);
        });
        SseBroadcaster broadcaster = new SseBroadcaster(eventBus);
        FakeHttpExchange exchange = new FakeHttpExchange("GET", URI.create("/api/stream"));

        try {
            Thread.currentThread().interrupt();
            broadcaster.handle(exchange);
        } finally {
            Thread.interrupted();
        }

        assertEquals(200, exchange.responseCode);
        assertTrue(exchange.responseHeaders.getFirst("Content-Type").contains("text/event-stream"));
        assertEquals(0, broadcaster.clientCount());
    }

    private static final class FakeHttpExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final URI uri;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private int responseCode = -1;

        private FakeHttpExchange(String method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
            try {
                responseBody.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.responseCode = rCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
            throw new UnsupportedOperationException("not needed in tests");
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}

package com.signalsentinel.service.api;

import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.Event;
import com.signalsentinel.service.store.EventCodec;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class SseBroadcaster {

    /**
     * Event types that carry PII (email addresses, auth state) and must not be
     * sent to unauthenticated SSE clients.
     */
    static final Set<String> RESTRICTED_EVENT_TYPES = Set.of(
            "LoginFailed",
            "LoginSucceeded",
            "UserRegistered",
            "PasswordResetRequested",
            "PasswordResetSucceeded",
            "PasswordResetFailed"
    );

    private final List<SseClient> clients = new CopyOnWriteArrayList<>();

    public SseBroadcaster(EventBus eventBus) {
        EventCodec.subscribeAll(eventBus, this::broadcast);
    }

    /**
     * Registers an SSE client. {@code authenticated} controls whether
     * restricted (PII-carrying) events are forwarded to this client.
     */
    public void handle(HttpExchange exchange, boolean authenticated) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        OutputStream out = exchange.getResponseBody();
        SseClient client = new SseClient(exchange, out, authenticated, Thread.currentThread());
        clients.add(client);

        try {
            writeRaw(client, ": connected\n\n");
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(15_000);
                writeRaw(client, ": keepalive\n\n");
            }
        } catch (IOException ignored) {
            // client disconnected
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            removeClient(client);
        }
    }

    public void broadcast(Event event) {
        String payload = "event: " + event.type() + "\n" +
                "data: " + EventCodec.toSseData(event) + "\n\n";

        boolean isRestricted = RESTRICTED_EVENT_TYPES.contains(event.type());

        for (SseClient client : clients) {
            if (isRestricted && !client.authenticated()) {
                continue;
            }
            try {
                writeRaw(client, payload);
            } catch (Exception e) {
                removeClient(client);
            }
        }
    }

    public int clientCount() {
        return clients.size();
    }

    /**
     * Interrupts all active SSE handler threads so they exit their keepalive sleep promptly.
     * Call this before stopping the HTTP server to avoid waiting out the full stop grace period.
     */
    public void closeAll() {
        for (SseClient client : clients) {
            client.handlerThread().interrupt();
        }
    }

    private void writeRaw(SseClient client, String data) throws IOException {
        synchronized (client) {
            client.outputStream().write(data.getBytes(StandardCharsets.UTF_8));
            client.outputStream().flush();
        }
    }

    private void removeClient(SseClient client) {
        clients.remove(client);
        client.handlerThread().interrupt();
        client.close();
    }

    private record SseClient(HttpExchange exchange, OutputStream outputStream, boolean authenticated, Thread handlerThread) {
        private void close() {
            try {
                outputStream.close();
            } catch (IOException ignored) {
            }
            exchange.close();
        }
    }
}

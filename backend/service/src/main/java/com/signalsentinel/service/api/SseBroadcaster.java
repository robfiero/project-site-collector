package com.signalsentinel.service.api;

import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.Event;
import com.signalsentinel.service.store.EventCodec;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class SseBroadcaster {
    private final List<SseClient> clients = new CopyOnWriteArrayList<>();

    public SseBroadcaster(EventBus eventBus) {
        EventCodec.subscribeAll(eventBus, this::broadcast);
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, 0);

        OutputStream out = exchange.getResponseBody();
        SseClient client = new SseClient(exchange, out);
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

        for (SseClient client : clients) {
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

    private void writeRaw(SseClient client, String data) throws IOException {
        synchronized (client) {
            client.outputStream().write(data.getBytes(StandardCharsets.UTF_8));
            client.outputStream().flush();
        }
    }

    private void removeClient(SseClient client) {
        clients.remove(client);
        client.close();
    }

    private record SseClient(HttpExchange exchange, OutputStream outputStream) {
        private void close() {
            try {
                outputStream.close();
            } catch (IOException ignored) {
            }
            exchange.close();
        }
    }
}

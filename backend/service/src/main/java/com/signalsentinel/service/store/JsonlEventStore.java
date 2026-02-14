package com.signalsentinel.service.store;

import com.signalsentinel.core.events.Event;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

public class JsonlEventStore implements EventStore {
    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();

    public JsonlEventStore(Path file) {
        this.file = file;
    }

    @Override
    public void append(Event event) {
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(
                    file,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            )) {
                writer.write(EventCodec.toJsonLine(event));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed appending event", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Event> query(Instant since, Optional<String> type, int limit) {
        lock.lock();
        try {
            if (!Files.exists(file)) {
                return List.of();
            }
            List<Event> events = new ArrayList<>();
            int lineNumber = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                Event event;
                try {
                    event = EventCodec.fromJsonLine(line);
                } catch (RuntimeException decodeError) {
                    throw new IllegalStateException("Invalid JSONL event at line " + lineNumber, decodeError);
                }
                if (event.timestamp().isBefore(since)) {
                    continue;
                }
                if (type.isPresent() && !type.get().equals(event.type())) {
                    continue;
                }
                events.add(event);
            }
            if (events.size() <= limit) {
                return events;
            }
            return events.subList(events.size() - limit, events.size());
        } catch (IOException e) {
            throw new IllegalStateException("Failed querying events", e);
        } finally {
            lock.unlock();
        }
    }
}

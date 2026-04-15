package com.signalsentinel.service.store;

import com.signalsentinel.core.events.Event;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

public class JsonlEventStore implements EventStore {
    static final int DEFAULT_CACHE_CAPACITY = 1_000;

    private final Path file;
    private final int cacheCapacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<Event> cache = new ArrayDeque<>();

    public JsonlEventStore(Path file) {
        this(file, DEFAULT_CACHE_CAPACITY);
    }

    JsonlEventStore(Path file, int cacheCapacity) {
        this.file = file;
        this.cacheCapacity = cacheCapacity;
        loadCacheIfPresent();
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
            if (cache.size() >= cacheCapacity) {
                cache.removeFirst();
            }
            cache.addLast(event);
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
            List<Event> events = new ArrayList<>();
            for (Event event : cache) {
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
        } finally {
            lock.unlock();
        }
    }

    private void loadCacheIfPresent() {
        lock.lock();
        try {
            if (!Files.exists(file)) {
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            // Only load the most recent cacheCapacity lines to bound startup time.
            int start = Math.max(0, lines.size() - cacheCapacity);
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                Event event;
                try {
                    event = EventCodec.fromJsonLine(line);
                } catch (RuntimeException decodeError) {
                    throw new IllegalStateException("Invalid JSONL event at line " + (i + 1), decodeError);
                }
                cache.addLast(event);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed loading events from " + file, e);
        } finally {
            lock.unlock();
        }
    }
}

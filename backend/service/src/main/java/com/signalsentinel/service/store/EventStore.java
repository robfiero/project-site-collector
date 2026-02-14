package com.signalsentinel.service.store;

import com.signalsentinel.core.events.Event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EventStore {
    void append(Event event);

    List<Event> query(Instant since, Optional<String> type, int limit);
}

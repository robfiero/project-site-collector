package com.signalsentinel.collectors.support;

import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import com.signalsentinel.core.events.Event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventCapture {
    private final List<Event> events = new CopyOnWriteArrayList<>();

    public EventCapture(EventBus bus) {
        bus.subscribe(CollectorTickStarted.class, events::add);
        bus.subscribe(CollectorTickCompleted.class, events::add);
        bus.subscribe(com.signalsentinel.core.events.SiteFetched.class, events::add);
        bus.subscribe(com.signalsentinel.core.events.ContentChanged.class, events::add);
        bus.subscribe(com.signalsentinel.core.events.NewsUpdated.class, events::add);
        bus.subscribe(com.signalsentinel.core.events.WeatherUpdated.class, events::add);
        bus.subscribe(com.signalsentinel.core.events.AlertRaised.class, events::add);
    }

    public <T extends Event> List<T> byType(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    public List<Event> all() {
        return List.copyOf(events);
    }
}

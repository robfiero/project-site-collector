package com.signalsentinel.core.bus;

import com.signalsentinel.core.events.Event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EventBus {
    private static final Logger LOGGER = Logger.getLogger(EventBus.class.getName());

    private final Map<Class<? extends Event>, CopyOnWriteArrayList<Consumer<? extends Event>>> subscribers =
            new ConcurrentHashMap<>();
    private final BiConsumer<Event, Exception> onHandlerError;

    public EventBus() {
        this((event, ex) -> LOGGER.log(Level.WARNING, "Event handler failed for type " + event.type(), ex));
    }

    public EventBus(BiConsumer<Event, Exception> onHandlerError) {
        this.onHandlerError = onHandlerError;
    }

    public <T extends Event> void subscribe(Class<T> type, Consumer<T> handler) {
        subscribers.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>()).add(handler);
    }

    public void publish(Event event) {
        List<Consumer<? extends Event>> handlers = subscribers.getOrDefault(event.getClass(), new CopyOnWriteArrayList<>());
        for (Consumer<? extends Event> rawHandler : handlers) {
            invokeHandler(rawHandler, event, onHandlerError);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Event> void invokeHandler(
            Consumer<? extends Event> rawHandler,
            Event event,
            BiConsumer<Event, Exception> onHandlerError
    ) {
        try {
            Consumer<T> typedHandler = (Consumer<T>) rawHandler;
            typedHandler.accept((T) event);
        } catch (Exception ex) {
            onHandlerError.accept(event, ex);
        }
    }
}

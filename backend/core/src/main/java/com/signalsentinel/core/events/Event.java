package com.signalsentinel.core.events;

import java.time.Instant;

public interface Event {
    Instant timestamp();

    String type();
}

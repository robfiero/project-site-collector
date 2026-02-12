package com.signalsentinel.collectors.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

public class MutableClock extends Clock {
    private final ZoneId zone;
    private final AtomicReference<Instant> instant;

    public MutableClock(Instant initial, ZoneId zone) {
        this.zone = zone;
        this.instant = new AtomicReference<>(initial);
    }

    public void setInstant(Instant next) {
        instant.set(next);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant(), zone);
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}

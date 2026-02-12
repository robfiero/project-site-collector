package com.signalsentinel.collectors.api;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface Collector {
    String name();

    Duration interval();

    CompletableFuture<CollectorResult> poll(CollectorContext ctx);
}

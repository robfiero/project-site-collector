package com.signalsentinel.collectors.support;

import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.api.CollectorResult;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import org.junit.jupiter.api.Assertions;

import java.time.Duration;

public final class CollectorContractAssertions {
    private CollectorContractAssertions() {
    }

    public static CollectorResult assertContract(
            Collector collector,
            CollectorContext ctx,
            EventCapture capture,
            Duration budget,
            boolean expectPartialFailure
    ) {
        int startedBefore = capture.byType(CollectorTickStarted.class).size();
        int completedBefore = capture.byType(CollectorTickCompleted.class).size();

        CollectorResult result = Assertions.assertTimeoutPreemptively(budget, () -> collector.poll(ctx).join());

        int startedAfter = capture.byType(CollectorTickStarted.class).size();
        int completedAfter = capture.byType(CollectorTickCompleted.class).size();
        Assertions.assertEquals(startedBefore + 1, startedAfter, "collector should emit one start event");
        Assertions.assertEquals(completedBefore + 1, completedAfter, "collector should emit one completion event");

        if (expectPartialFailure) {
            Assertions.assertFalse(result.success(), "partial failure should report unsuccessful result");
        }

        return result;
    }
}

package com.signalsentinel.collectors.api;

import java.util.Map;

public record CollectorResult(boolean success, String message, Map<String, Object> stats) {
    public static CollectorResult success(String message, Map<String, Object> stats) {
        return new CollectorResult(true, message, stats);
    }

    public static CollectorResult failure(String message, Map<String, Object> stats) {
        return new CollectorResult(false, message, stats);
    }
}

package com.signalsentinel.core.model;

import java.util.Map;

public record CollectorConfig(
        String name,
        boolean enabled,
        int intervalSeconds,
        Map<String, Object> params
) {
}

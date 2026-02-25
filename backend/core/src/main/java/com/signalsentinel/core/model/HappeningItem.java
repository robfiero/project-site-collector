package com.signalsentinel.core.model;

public record HappeningItem(
        String id,
        String name,
        String startDateTime,
        String venueName,
        String city,
        String state,
        String url,
        String category,
        String source
) {
}

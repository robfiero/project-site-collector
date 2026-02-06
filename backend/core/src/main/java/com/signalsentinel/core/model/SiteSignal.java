package com.signalsentinel.core.model;

import java.time.Instant;

public record SiteSignal(
        String siteId,
        String url,
        String hash,
        String title,
        int linkCount,
        Instant lastChecked,
        Instant lastChanged
) {
}

package com.signalsentinel.core.model;

import java.util.Map;

public record SignalSnapshot(
        Map<String, SiteSignal> siteSignals,
        Map<String, NewsSignal> newsSignals,
        Map<String, WeatherSignal> weatherSignals
) {
}

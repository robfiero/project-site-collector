package com.signalsentinel.collectors.api;

import com.signalsentinel.core.model.NewsSignal;
import com.signalsentinel.core.model.SiteSignal;
import com.signalsentinel.core.model.WeatherSignal;

import java.util.Optional;

public interface SignalStore {
    Optional<SiteSignal> getSite(String siteId);

    void putSite(SiteSignal signal);

    void putNews(NewsSignal signal);

    void putWeather(WeatherSignal signal);
}

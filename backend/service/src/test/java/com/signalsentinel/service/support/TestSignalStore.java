package com.signalsentinel.service.support;

import com.signalsentinel.collectors.api.SignalStore;
import com.signalsentinel.core.model.NewsSignal;
import com.signalsentinel.core.model.SiteSignal;
import com.signalsentinel.core.model.WeatherSignal;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class TestSignalStore implements SignalStore {
    private final ConcurrentHashMap<String, SiteSignal> sites = new ConcurrentHashMap<>();

    @Override
    public Optional<SiteSignal> getSite(String siteId) {
        return Optional.ofNullable(sites.get(siteId));
    }

    @Override
    public void putSite(SiteSignal signal) {
        sites.put(signal.siteId(), signal);
    }

    @Override
    public void putNews(NewsSignal signal) {
    }

    @Override
    public void putWeather(WeatherSignal signal) {
    }
}

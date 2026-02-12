package com.signalsentinel.collectors.support;

import com.signalsentinel.collectors.api.SignalStore;
import com.signalsentinel.core.model.NewsSignal;
import com.signalsentinel.core.model.SiteSignal;
import com.signalsentinel.core.model.WeatherSignal;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySignalStore implements SignalStore {
    private final Map<String, SiteSignal> sites = new ConcurrentHashMap<>();
    private final Map<String, NewsSignal> news = new ConcurrentHashMap<>();
    private final Map<String, WeatherSignal> weather = new ConcurrentHashMap<>();

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
        news.put(signal.source(), signal);
    }

    @Override
    public void putWeather(WeatherSignal signal) {
        weather.put(signal.location(), signal);
    }

    public Optional<NewsSignal> getNews(String source) {
        return Optional.ofNullable(news.get(source));
    }

    public Optional<WeatherSignal> getWeather(String location) {
        return Optional.ofNullable(weather.get(location));
    }
}

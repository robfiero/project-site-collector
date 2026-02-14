package com.signalsentinel.service.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signalsentinel.core.model.NewsSignal;
import com.signalsentinel.core.model.SiteSignal;
import com.signalsentinel.core.model.WeatherSignal;
import com.signalsentinel.core.util.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class JsonFileSignalStore implements ServiceSignalStore {
    private static final ObjectMapper MAPPER = JsonUtils.objectMapper();

    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, SiteSignal> sites = new ConcurrentHashMap<>();
    private final Map<String, NewsSignal> news = new ConcurrentHashMap<>();
    private final Map<String, WeatherSignal> weather = new ConcurrentHashMap<>();

    public JsonFileSignalStore(Path file) {
        this.file = file;
        loadIfPresent();
    }

    @Override
    public Optional<SiteSignal> getSite(String siteId) {
        return Optional.ofNullable(sites.get(siteId));
    }

    @Override
    public void putSite(SiteSignal signal) {
        sites.put(signal.siteId(), signal);
        persist();
    }

    @Override
    public void putNews(NewsSignal signal) {
        news.put(signal.source(), signal);
        persist();
    }

    @Override
    public void putWeather(WeatherSignal signal) {
        weather.put(signal.location(), signal);
        persist();
    }

    @Override
    public Map<String, Object> getAllSignals() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("sites", new HashMap<>(sites));
        snapshot.put("news", new HashMap<>(news));
        snapshot.put("weather", new HashMap<>(weather));
        return snapshot;
    }

    private void loadIfPresent() {
        lock.lock();
        try {
            if (!Files.exists(file)) {
                return;
            }
            try (InputStream in = Files.newInputStream(file)) {
                SignalSnapshotFile loaded = MAPPER.readValue(in, SignalSnapshotFile.class);
                if (loaded.sites() != null) {
                    sites.putAll(loaded.sites());
                }
                if (loaded.news() != null) {
                    news.putAll(loaded.news());
                }
                if (loaded.weather() != null) {
                    weather.putAll(loaded.weather());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed loading signals from " + file, e);
        } finally {
            lock.unlock();
        }
    }

    private void persist() {
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(out,
                        new SignalSnapshotFile(new HashMap<>(sites), new HashMap<>(news), new HashMap<>(weather)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed writing signals to " + file, e);
        } finally {
            lock.unlock();
        }
    }

    private record SignalSnapshotFile(
            Map<String, SiteSignal> sites,
            Map<String, NewsSignal> news,
            Map<String, WeatherSignal> weather
    ) {
    }
}

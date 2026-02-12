package com.signalsentinel.collectors.weather;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.signalsentinel.core.util.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MockWeatherProvider implements WeatherProvider {
    private final Map<String, WeatherReading> readings = new ConcurrentHashMap<>();

    public MockWeatherProvider(Path jsonFile) {
        try (InputStream in = Files.newInputStream(jsonFile)) {
            WeatherFixture fixture = JsonUtils.objectMapper().readValue(in, WeatherFixture.class);
            fixture.locations().forEach(entry -> readings.put(
                    entry.location(),
                    new WeatherReading(entry.location(), entry.tempF(), entry.conditions(), entry.alerts())
            ));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed reading weather fixture: " + jsonFile, e);
        }
    }

    @Override
    public WeatherReading getReading(String location) {
        WeatherReading reading = readings.get(location);
        if (reading == null) {
            throw new IllegalArgumentException("No mock weather configured for location: " + location);
        }
        return reading;
    }

    private record WeatherFixture(List<WeatherEntry> locations) {
    }

    private record WeatherEntry(
            String location,
            double tempF,
            String conditions,
            @JsonProperty(defaultValue = "[]") List<String> alerts
    ) {
    }
}

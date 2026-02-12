package com.signalsentinel.collectors.weather;

import java.util.List;

public record WeatherReading(String location, double tempF, String conditions, List<String> alerts) {
}

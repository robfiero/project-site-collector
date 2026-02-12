package com.signalsentinel.collectors.weather;

public interface WeatherProvider {
    WeatherReading getReading(String location);
}

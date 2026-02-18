package com.signalsentinel.service.env;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class EnvService {
    private final ZipGeoStore zipGeoStore;
    private final ZipGeoResolver zipGeoResolver;
    private final BiFunction<Double, Double, NoaaWeatherSnapshot> weatherLookup;
    private final Function<String, Optional<AirNowAqiSnapshot>> aqiLookup;
    private final Clock clock;
    private final List<String> defaultZips;

    public EnvService(
            ZipGeoStore zipGeoStore,
            ZipGeoResolver zipGeoResolver,
            NoaaClient noaaClient,
            AirNowClient airNowClient,
            Clock clock,
            List<String> defaultZips
    ) {
        this(
                zipGeoStore,
                zipGeoResolver,
                noaaClient::currentFor,
                airNowClient::currentForZip,
                clock,
                defaultZips
        );
    }

    public EnvService(
            ZipGeoStore zipGeoStore,
            ZipGeoResolver zipGeoResolver,
            BiFunction<Double, Double, NoaaWeatherSnapshot> weatherLookup,
            Function<String, Optional<AirNowAqiSnapshot>> aqiLookup,
            Clock clock,
            List<String> defaultZips
    ) {
        this.zipGeoStore = zipGeoStore;
        this.zipGeoResolver = zipGeoResolver;
        this.weatherLookup = weatherLookup;
        this.aqiLookup = aqiLookup;
        this.clock = clock;
        this.defaultZips = List.copyOf(defaultZips);
    }

    public List<EnvStatus> getStatuses(List<String> zips) {
        List<String> effectiveZips = normalizeZipList(zips.isEmpty() ? defaultZips : zips);
        List<EnvStatus> statuses = new ArrayList<>();
        for (String zip : effectiveZips) {
            statuses.add(resolveStatus(zip));
        }
        return statuses;
    }

    private EnvStatus resolveStatus(String zip) {
        ZipGeoRecord geo = zipGeoStore.get(zip).orElseGet(() -> {
            ZipGeoRecord resolved = zipGeoResolver.resolve(zip);
            zipGeoStore.put(resolved);
            return resolved;
        });

        NoaaWeatherSnapshot weatherSnapshot = weatherLookup.apply(geo.lat(), geo.lon());
        EnvStatus.Weather weather = new EnvStatus.Weather(
                weatherSnapshot.temperatureF(),
                weatherSnapshot.shortForecast(),
                weatherSnapshot.windSpeed(),
                weatherSnapshot.observedAt(),
                "NOAA",
                weatherSnapshot.requestUrl(),
                weatherSnapshot.observationTime()
        );

        Optional<AirNowAqiSnapshot> aqiSnapshot = Optional.empty();
        String message = null;
        try {
            aqiSnapshot = aqiLookup.apply(zip);
            if (aqiSnapshot.isEmpty()) {
                message = "AQI unavailable";
            }
        } catch (RuntimeException airNowError) {
            message = "AQI unavailable";
        }

        EnvStatus.AirQuality aqi;
        if (aqiSnapshot.isPresent()) {
            AirNowAqiSnapshot snapshot = aqiSnapshot.get();
            aqi = new EnvStatus.AirQuality(
                    snapshot.aqi(),
                    snapshot.category(),
                    snapshot.observedAt(),
                    null,
                    "AirNow",
                    snapshot.requestUrl(),
                    snapshot.validDateTime()
            );
        } else {
            aqi = new EnvStatus.AirQuality(
                    null,
                    null,
                    Instant.now(clock),
                    message,
                    "AirNow",
                    null,
                    null
            );
        }

        return new EnvStatus(zip, geo.lat(), geo.lon(), weather, aqi, Instant.now(clock));
    }

    private static List<String> normalizeZipList(List<String> zips) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String zip : zips) {
            if (zip == null) {
                continue;
            }
            String value = zip.trim();
            if (!value.matches("\\d{5}")) {
                continue;
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }
}

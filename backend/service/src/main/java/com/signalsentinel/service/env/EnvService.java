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
    private final boolean airNowConfigured;
    private final boolean noaaUserAgentPresent;
    private final boolean noaaFollowRedirects;

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
                defaultZips,
                airNowClient.isConfigured(),
                noaaClient.hasUserAgentConfigured(),
                noaaClient.followsRedirects()
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
        this(zipGeoStore, zipGeoResolver, weatherLookup, aqiLookup, clock, defaultZips, true, true, true);
    }

    EnvService(
            ZipGeoStore zipGeoStore,
            ZipGeoResolver zipGeoResolver,
            BiFunction<Double, Double, NoaaWeatherSnapshot> weatherLookup,
            Function<String, Optional<AirNowAqiSnapshot>> aqiLookup,
            Clock clock,
            List<String> defaultZips,
            boolean airNowConfigured,
            boolean noaaUserAgentPresent,
            boolean noaaFollowRedirects
    ) {
        this.zipGeoStore = zipGeoStore;
        this.zipGeoResolver = zipGeoResolver;
        this.weatherLookup = weatherLookup;
        this.aqiLookup = aqiLookup;
        this.clock = clock;
        this.defaultZips = List.copyOf(defaultZips);
        this.airNowConfigured = airNowConfigured;
        this.noaaUserAgentPresent = noaaUserAgentPresent;
        this.noaaFollowRedirects = noaaFollowRedirects;
    }

    public List<EnvStatus> getStatuses(List<String> zips) {
        return getStatuses(zips, true);
    }

    public List<EnvStatus> getStatuses(List<String> zips, boolean includeAqi) {
        List<String> effectiveZips = normalizeZipList(zips.isEmpty() ? defaultZips : zips);
        List<EnvStatus> statuses = new ArrayList<>();
        for (String zip : effectiveZips) {
            try {
                statuses.add(resolveStatus(zip, includeAqi));
            } catch (RuntimeException e) {
                statuses.add(unavailableStatus(zip, e));
            }
        }
        return statuses;
    }

    private EnvStatus resolveStatus(String zip, boolean includeAqi) {
        ZipGeoRecord geo = zipGeoStore.get(zip)
                .filter(r -> r.city() != null && !r.city().isBlank())
                .orElseGet(() -> {
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
        if (includeAqi) {
            try {
                aqiSnapshot = aqiLookup.apply(zip);
                if (aqiSnapshot.isEmpty()) {
                    message = "AQI unavailable";
                }
            } catch (RuntimeException airNowError) {
                message = "AQI unavailable";
            }
        } else {
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

        String locationLabel = buildLocationLabel(zip, geo.city(), geo.state(), weatherSnapshot.city(), weatherSnapshot.state());
        return new EnvStatus(zip, locationLabel, geo.lat(), geo.lon(), weather, aqi, Instant.now(clock));
    }

    /**
     * Returns a human-readable location label for a ZIP code.
     * Checks the geo cache first; if missing, resolves via the offline dataset (in-memory, no
     * network call for the ~41k ZIPs in the bundled file) and caches the result for future use.
     */
    public String labelForZip(String zip) {
        if (zip == null || !zip.matches("\\d{5}")) {
            return "ZIP " + zip;
        }
        ZipGeoRecord geo = zipGeoStore.get(zip)
                .filter(r -> r.city() != null && !r.city().isBlank())
                .orElseGet(() -> {
                    try {
                        ZipGeoRecord resolved = zipGeoResolver.resolve(zip);
                        zipGeoStore.put(resolved);
                        return resolved;
                    } catch (Exception e) {
                        return null;
                    }
                });
        if (geo == null) {
            return "ZIP " + zip;
        }
        return buildLocationLabel(zip, geo.city(), geo.state(), null, null);
    }

    public boolean isAirNowConfigured() {
        return airNowConfigured;
    }

    public boolean isNoaaUserAgentPresent() {
        return noaaUserAgentPresent;
    }

    public boolean isNoaaFollowRedirects() {
        return noaaFollowRedirects;
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

    private static String buildLocationLabel(String zip, String geoCity, String geoState,
                                              String noaaCity, String noaaState) {
        // Prefer the offline geo record's city/state (always available for known ZIPs).
        // Fall back to whatever NOAA returns in its weather response.
        String city  = geoCity  != null && !geoCity.isBlank()  ? geoCity  : noaaCity;
        String state = geoState != null && !geoState.isBlank() ? geoState : noaaState;
        if (city != null && !city.isBlank() && state != null && !state.isBlank()) {
            return city + ", " + state + " (" + zip + ")";
        }
        return "ZIP " + zip;
    }

    private EnvStatus unavailableStatus(String zip, RuntimeException error) {
        Instant now = Instant.now(clock);
        String message = unavailableMessage(error);
        return new EnvStatus(
                zip,
                "ZIP " + zip,
                Double.NaN,
                Double.NaN,
                new EnvStatus.Weather(
                        null,
                        message,
                        "",
                        now,
                        "NOAA",
                        null,
                        null
                ),
                new EnvStatus.AirQuality(
                        null,
                        null,
                        now,
                        message,
                        "AirNow",
                        null,
                        null
                ),
                now
        );
    }

    private static String unavailableMessage(RuntimeException error) {
        String rootMessage = error.getMessage();
        if (rootMessage != null && rootMessage.toLowerCase().contains("zip")) {
            return "Unable to resolve ZIP to location. Try a nearby ZIP code.";
        }
        return "Environment data unavailable for this ZIP right now.";
    }
}

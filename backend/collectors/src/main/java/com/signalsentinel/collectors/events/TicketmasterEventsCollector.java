package com.signalsentinel.collectors.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.api.CollectorResult;
import com.signalsentinel.collectors.config.TicketmasterCollectorConfig;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import com.signalsentinel.core.events.LocalHappeningsIngested;
import com.signalsentinel.core.model.HappeningItem;
import com.signalsentinel.core.model.LocalHappeningsSignal;
import com.signalsentinel.core.util.JsonUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class TicketmasterEventsCollector implements Collector {
    public static final String CONFIG_KEY = "ticketmasterEventsCollector";
    private static final Logger LOGGER = Logger.getLogger(TicketmasterEventsCollector.class.getName());
    private static final String SOURCE_NAME = "ticketmaster";
    private static final String SOURCE_ATTRIBUTION = "Powered by Ticketmaster";
    private static final int PAGE_SIZE = 20;
    private static final int MAX_ATTEMPTS = 3;

    private final Duration interval;

    public TicketmasterEventsCollector(Duration interval) {
        this.interval = Objects.requireNonNull(interval, "interval is required");
    }

    @Override
    public String name() {
        return "localEventsCollector";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public CompletableFuture<CollectorResult> poll(CollectorContext ctx) {
        Instant tickStartedAt = ctx.clock().instant();
        ctx.eventBus().publish(new CollectorTickStarted(tickStartedAt, name()));
        return CompletableFuture.supplyAsync(() -> runPoll(ctx))
                .handle((result, error) -> {
                    long durationMillis = Duration.between(tickStartedAt, ctx.clock().instant()).toMillis();
                    if (error != null) {
                        String message = "Ticketmaster poll failed: " + rootMessage(error);
                        ctx.eventBus().publish(new AlertRaised(
                                ctx.clock().instant(),
                                "collector",
                                message,
                                Map.of("collector", name())
                        ));
                        ctx.eventBus().publish(new CollectorTickCompleted(ctx.clock().instant(), name(), false, durationMillis));
                        return CollectorResult.failure(message, Map.of("collector", name()));
                    }
                    ctx.eventBus().publish(new CollectorTickCompleted(ctx.clock().instant(), name(), result.success(), durationMillis));
                    return result;
                });
    }

    private CollectorResult runPoll(CollectorContext ctx) {
        TicketmasterCollectorConfig cfg = ctx.requiredConfig(CONFIG_KEY, TicketmasterCollectorConfig.class);
        if (cfg.apiKey().isBlank()) {
            String message = "Ticketmaster collector disabled: missing TICKETMASTER_API_KEY";
            LOGGER.warning(message);
            return CollectorResult.success(message, Map.of("collector", name(), "items", 0));
        }

        List<String> requestedZips = normalizeZipCodes(cfg.zipSupplier().get());
        if (requestedZips.isEmpty()) {
            return CollectorResult.success("Ticketmaster polling skipped: no ZIP codes configured", Map.of("collector", name(), "items", 0, "zips", List.of()));
        }

        int totalItems = 0;
        int failedZips = 0;
        for (String zip : requestedZips) {
            ZipPollOutcome outcome = pollZip(zip, cfg, ctx);
            if (outcome.authFailed()) {
                return CollectorResult.failure(outcome.message(), Map.of("collector", name(), "items", totalItems, "zips", requestedZips));
            }
            if (!outcome.success()) {
                failedZips++;
            }
            totalItems += outcome.items().size();
            ctx.signalStore().putLocalHappenings(new LocalHappeningsSignal(
                    zip,
                    outcome.items(),
                    SOURCE_ATTRIBUTION,
                    ctx.clock().instant()
            ));
            ctx.eventBus().publish(new LocalHappeningsIngested(
                    ctx.clock().instant(),
                    SOURCE_NAME,
                    zip,
                    outcome.items().size()
            ));
        }
        if (failedZips == 0) {
            return CollectorResult.success("Ticketmaster polling completed", Map.of("collector", name(), "items", totalItems, "zips", requestedZips));
        }
        return CollectorResult.failure("Ticketmaster polling had failures", Map.of("collector", name(), "items", totalItems, "zips", requestedZips, "failures", failedZips));
    }

    private ZipPollOutcome pollZip(String zip, TicketmasterCollectorConfig cfg, CollectorContext ctx) {
        HttpRequest request = HttpRequest.newBuilder(buildRequestUri(cfg, zip))
                .GET()
                .timeout(ctx.requestTimeout())
                .header("Accept", "application/json")
                .header("User-Agent", "SignalSentinel/0.1")
                .build();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = ctx.httpClient().send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 401 || status == 403) {
                    String message = "Ticketmaster request unauthorized (HTTP " + status + "); collector will retry next poll";
                    LOGGER.warning(message);
                    ctx.eventBus().publish(new AlertRaised(
                            ctx.clock().instant(),
                            "collector",
                            message,
                            Map.of("collector", name(), "status", status, "zip", zip)
                    ));
                    return new ZipPollOutcome(false, true, List.of(), message);
                }
                if (status >= 500 && attempt < MAX_ATTEMPTS) {
                    sleepBackoff(attempt);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    String message = "Ticketmaster request failed for ZIP " + zip + " with HTTP " + status;
                    ctx.eventBus().publish(new AlertRaised(
                            ctx.clock().instant(),
                            "collector",
                            message,
                            Map.of("collector", name(), "status", status, "zip", zip)
                    ));
                    return new ZipPollOutcome(false, false, List.of(), message);
                }
                return new ZipPollOutcome(true, false, parseEvents(response.body()), "ok");
            } catch (IOException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new UncheckedIOException(e);
                }
                sleepBackoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Ticketmaster polling interrupted", e);
            }
        }
        return new ZipPollOutcome(false, false, List.of(), "Ticketmaster polling failed for ZIP " + zip);
    }

    static List<HappeningItem> parseEvents(String responseBody) {
        JsonNode root = readJson(responseBody);
        JsonNode events = root.path("_embedded").path("events");
        if (!events.isArray()) {
            return List.of();
        }

        Map<String, HappeningItem> deduped = new LinkedHashMap<>();
        for (JsonNode event : events) {
            String id = text(event.path("id"));
            if (id == null || id.isBlank()) {
                continue;
            }
            deduped.computeIfAbsent(id, ignored -> toItem(event, id));
        }
        return deduped.values().stream()
                .sorted(Comparator
                        .comparing(TicketmasterEventsCollector::sortInstantForStartDateTime)
                        .thenComparing(HappeningItem::id))
                .toList();
    }

    private static HappeningItem toItem(JsonNode event, String id) {
        JsonNode venue = event.path("_embedded").path("venues").isArray() && event.path("_embedded").path("venues").size() > 0
                ? event.path("_embedded").path("venues").get(0)
                : JsonUtils.objectMapper().createObjectNode();
        String city = text(venue.path("city").path("name"));
        String state = text(venue.path("state").path("stateCode"));
        String category = classification(event.path("classifications"));
        String startDateTime = firstNonBlank(
                text(event.path("dates").path("start").path("dateTime")),
                text(event.path("dates").path("start").path("localDate"))
        );
        return new HappeningItem(
                id,
                firstNonBlank(text(event.path("name")), "Untitled event"),
                startDateTime,
                firstNonBlank(text(venue.path("name")), "Unknown venue"),
                firstNonBlank(city, "Unknown city"),
                firstNonBlank(state, ""),
                firstNonBlank(text(event.path("url")), ""),
                firstNonBlank(category, "unknown"),
                SOURCE_NAME
        );
    }

    private static URI buildRequestUri(TicketmasterCollectorConfig cfg, String zip) {
        StringBuilder query = new StringBuilder();
        appendQuery(query, "apikey", cfg.apiKey());
        appendQuery(query, "postalCode", zip);
        appendQuery(query, "countryCode", "US");
        appendQuery(query, "radius", String.valueOf(Math.max(1, cfg.radiusMiles())));
        appendQuery(query, "unit", "miles");
        appendQuery(query, "sort", "date,asc");
        appendQuery(query, "size", String.valueOf(PAGE_SIZE));
        if (!cfg.classifications().isEmpty()) {
            appendQuery(query, "classificationName", String.join(",", cfg.classifications()));
        }
        String normalizedBase = cfg.baseUrl().endsWith("/") ? cfg.baseUrl().substring(0, cfg.baseUrl().length() - 1) : cfg.baseUrl();
        return URI.create(normalizedBase + "/events.json?" + query);
    }

    private static void appendQuery(StringBuilder query, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
        query.append('=');
        query.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String classification(JsonNode classifications) {
        if (!classifications.isArray() || classifications.isEmpty()) {
            return null;
        }
        JsonNode first = classifications.get(0);
        return firstNonBlank(
                text(first.path("segment").path("name")),
                text(first.path("genre").path("name")),
                text(first.path("subGenre").path("name"))
        );
    }

    private static JsonNode readJson(String body) {
        try {
            return JsonUtils.objectMapper().readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException("Invalid Ticketmaster JSON response", e);
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(150L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during retry backoff", e);
        }
    }

    private static List<String> normalizeZipCodes(List<String> rawZips) {
        if (rawZips == null || rawZips.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String zip : rawZips) {
            if (zip == null) {
                continue;
            }
            String trimmed = zip.trim();
            if (trimmed.matches("\\d{5}")) {
                unique.add(trimmed);
            }
        }
        return List.copyOf(unique);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static Instant sortInstantForStartDateTime(HappeningItem item) {
        String value = item.startDateTime();
        if (value == null || value.isBlank()) {
            return Instant.MAX;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignoredAgain) {
                return Instant.MAX;
            }
        }
    }

    private record ZipPollOutcome(boolean success, boolean authFailed, List<HappeningItem> items, String message) {
    }
}

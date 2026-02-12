package com.signalsentinel.collectors.site;

import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.api.CollectorResult;
import com.signalsentinel.collectors.config.SiteCollectorConfig;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import com.signalsentinel.core.events.ContentChanged;
import com.signalsentinel.core.events.SiteFetched;
import com.signalsentinel.core.model.ParseMode;
import com.signalsentinel.core.model.SiteConfig;
import com.signalsentinel.core.model.SiteSignal;
import com.signalsentinel.core.util.HashingUtils;
import com.signalsentinel.core.util.HtmlUtils;

import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class SiteCollector implements Collector {
    public static final String CONFIG_KEY = "siteCollector";
    private final Duration interval;

    public SiteCollector() {
        this(Duration.ofSeconds(30));
    }

    public SiteCollector(Duration interval) {
        this.interval = interval;
    }

    @Override
    public String name() {
        return "siteCollector";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public CompletableFuture<CollectorResult> poll(CollectorContext ctx) {
        Instant tickStartedAt = ctx.clock().instant();
        ctx.eventBus().publish(new CollectorTickStarted(tickStartedAt, name()));

        SiteCollectorConfig cfg = ctx.requiredConfig(CONFIG_KEY, SiteCollectorConfig.class);
        List<CompletableFuture<SitePollOutcome>> tasks = cfg.sites().stream()
                .map(site -> pollSite(site, ctx))
                .toList();

        CompletableFuture<Void> all = CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
        CompletableFuture<CollectorResult> pipeline =
                all.thenApply(ignored -> summarize(cfg.sites(), tasks.stream().map(CompletableFuture::join).toList()));

        return pipeline.handle((result, error) -> {
            long durationMillis = Duration.between(tickStartedAt, ctx.clock().instant()).toMillis();
            if (error != null) {
                ctx.eventBus().publish(new CollectorTickCompleted(ctx.clock().instant(), name(), false, durationMillis));
                return CollectorResult.failure("Site collector failed: " + rootCause(error).getMessage(), Map.of());
            }
            ctx.eventBus().publish(new CollectorTickCompleted(
                    ctx.clock().instant(),
                    name(),
                    result.success(),
                    durationMillis
            ));
            return result;
        });
    }

    private CompletableFuture<SitePollOutcome> pollSite(SiteConfig site, CollectorContext ctx) {
        Instant startedAt = ctx.clock().instant();
        HttpRequest request = HttpRequest.newBuilder(URI.create(site.url()))
                .GET()
                .timeout(ctx.requestTimeout())
                .build();

        return ctx.httpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(ctx.requestTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .handle((response, error) -> {
                    long durationMillis = Duration.between(startedAt, ctx.clock().instant()).toMillis();
                    if (error != null) {
                        ctx.eventBus().publish(new SiteFetched(ctx.clock().instant(), site.id(), site.url(), 0, durationMillis));
                        ctx.eventBus().publish(new AlertRaised(
                                ctx.clock().instant(),
                                "collector",
                                classifyFailureMessage(site.url(), error),
                                Map.of("collector", name(), "siteId", site.id(), "url", site.url())
                        ));
                        return new SitePollOutcome(site, false, durationMillis, false);
                    }

                    if (response.statusCode() >= 400) {
                        ctx.eventBus().publish(new SiteFetched(
                                ctx.clock().instant(),
                                site.id(),
                                site.url(),
                                response.statusCode(),
                                durationMillis
                        ));
                        ctx.eventBus().publish(new AlertRaised(
                                ctx.clock().instant(),
                                "collector",
                                "HTTP status " + response.statusCode() + " from " + site.url(),
                                Map.of(
                                        "collector", name(),
                                        "siteId", site.id(),
                                        "url", site.url(),
                                        "status", response.statusCode()
                                )
                        ));
                        return new SitePollOutcome(site, false, durationMillis, false);
                    }

                    SiteSignal baseSignal = toSignal(site, response.body(), ctx, startedAt);
                    Optional<SiteSignal> previous = ctx.signalStore().getSite(site.id());
                    boolean changed = previous.map(prev -> !prev.hash().equals(baseSignal.hash())).orElse(false);
                    SiteSignal updated = baseSignal;
                    if (changed && previous.isPresent()) {
                        ctx.eventBus().publish(new ContentChanged(
                                ctx.clock().instant(),
                                site.id(),
                                site.url(),
                                previous.get().hash(),
                                updated.hash()
                        ));
                        updated = new SiteSignal(
                                updated.siteId(),
                                updated.url(),
                                updated.hash(),
                                updated.title(),
                                updated.linkCount(),
                                updated.lastChecked(),
                                ctx.clock().instant()
                        );
                    } else if (previous.isPresent()) {
                        updated = new SiteSignal(
                                updated.siteId(),
                                updated.url(),
                                updated.hash(),
                                updated.title(),
                                updated.linkCount(),
                                updated.lastChecked(),
                                previous.get().lastChanged()
                        );
                    }

                    ctx.signalStore().putSite(updated);
                    ctx.eventBus().publish(new SiteFetched(
                            ctx.clock().instant(),
                            site.id(),
                            site.url(),
                            response.statusCode(),
                            durationMillis
                    ));
                    return new SitePollOutcome(site, true, durationMillis, changed);
                });
    }

    private String classifyFailureMessage(String url, Throwable error) {
        Throwable root = rootCause(error);
        String rootText = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        String lowered = rootText.toLowerCase(java.util.Locale.ROOT);
        String host = "";
        try {
            host = URI.create(url).getHost();
        } catch (Exception ignored) {
        }
        if (host != null && host.endsWith(".invalid")) {
            return "DNS/unknown host while fetching " + url + ": " + rootText;
        }
        if (root instanceof UnknownHostException
                || lowered.contains("unknown host")
                || lowered.contains("name or service")
                || lowered.contains("not known")
                || lowered.contains("nodename")) {
            return "DNS/unknown host while fetching " + url + ": " + rootText;
        }
        if (error instanceof java.util.concurrent.TimeoutException || lowered.contains("timed out")) {
            return "Request timed out while fetching " + url;
        }
        return "Fetch failure for " + url + ": " + rootText;
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private SiteSignal toSignal(SiteConfig site, String body, CollectorContext ctx, Instant startedAt) {
        String hash = HashingUtils.sha256(body);
        String title = "";
        int linkCount = 0;
        if (site.parseMode() == ParseMode.TITLE) {
            title = HtmlUtils.extractTitle(body).orElse("");
        } else if (site.parseMode() == ParseMode.LINKS) {
            linkCount = HtmlUtils.extractLinks(body).size();
        }

        return new SiteSignal(site.id(), site.url(), hash, title, linkCount, startedAt, startedAt);
    }

    private CollectorResult summarize(List<SiteConfig> sites, List<SitePollOutcome> outcomes) {
        long successCount = outcomes.stream().filter(SitePollOutcome::success).count();
        long changedCount = outcomes.stream().filter(SitePollOutcome::changed).count();
        double avgDuration = outcomes.stream().mapToLong(SitePollOutcome::durationMillis).average().orElse(0);

        Map<String, Long> byTag = outcomes.stream()
                .flatMap(outcome -> {
                    List<String> tags = outcome.site().tags();
                    return tags == null ? java.util.stream.Stream.<String>empty() : tags.stream();
                })
                .collect(Collectors.groupingBy(tag -> tag, Collectors.counting()));

        Map<String, Object> stats = new HashMap<>();
        stats.put("sites", sites.size());
        stats.put("successes", successCount);
        stats.put("changes", changedCount);
        stats.put("avgDurationMillis", avgDuration);
        stats.put("tagCounts", byTag);

        if (successCount == outcomes.size()) {
            return CollectorResult.success("Processed " + outcomes.size() + " sites", stats);
        }
        return CollectorResult.failure("Processed " + outcomes.size() + " sites with failures", stats);
    }

    private record SitePollOutcome(SiteConfig site, boolean success, long durationMillis, boolean changed) {
    }
}

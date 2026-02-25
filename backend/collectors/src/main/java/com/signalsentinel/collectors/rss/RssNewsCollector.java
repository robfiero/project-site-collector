package com.signalsentinel.collectors.rss;

import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.api.CollectorResult;
import com.signalsentinel.collectors.config.RssCollectorConfig;
import com.signalsentinel.collectors.config.RssSourceConfig;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import com.signalsentinel.core.events.NewsUpdated;
import com.signalsentinel.core.model.NewsSignal;
import com.signalsentinel.core.model.NewsStory;
import com.signalsentinel.core.util.JsonUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class RssNewsCollector implements Collector {
    public static final String CONFIG_KEY = "rssCollector";
    private static final Logger LOGGER = Logger.getLogger(RssNewsCollector.class.getName());
    private static final String DEFAULT_USER_AGENT = "SignalSentinel/0.1 (contact: support@example.com)";
    private final Duration interval;

    public RssNewsCollector() {
        this(Duration.ofSeconds(60));
    }

    public RssNewsCollector(Duration interval) {
        this.interval = interval;
    }

    @Override
    public String name() {
        return "rssCollector";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public CompletableFuture<CollectorResult> poll(CollectorContext ctx) {
        Instant tickStartedAt = ctx.clock().instant();
        ctx.eventBus().publish(new CollectorTickStarted(tickStartedAt, name()));

        RssCollectorConfig cfg = ctx.requiredConfig(CONFIG_KEY, RssCollectorConfig.class);
        List<CompletableFuture<RssPollOutcome>> tasks = cfg.sources().stream()
                .map(source -> pollSource(source, cfg, ctx))
                .toList();

        CompletableFuture<CollectorResult> pipeline = CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> summarize(tasks.stream().map(CompletableFuture::join).toList()));

        return pipeline.handle((result, error) -> {
            long durationMillis = Duration.between(tickStartedAt, ctx.clock().instant()).toMillis();
            if (error != null) {
                ctx.eventBus().publish(new CollectorTickCompleted(ctx.clock().instant(), name(), false, durationMillis));
                return CollectorResult.failure("RSS collector failed: " + rootMessage(error), Map.of());
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

    private CompletableFuture<RssPollOutcome> pollSource(RssSourceConfig source, RssCollectorConfig cfg, CollectorContext ctx) {
        String sourceId = source.source();
        String requestUrl = source.url();
        if ("npr".equalsIgnoreCase(sourceId)) {
            String nprApiKey = System.getenv().getOrDefault("NPR_API_KEY", "").trim();
            if (nprApiKey.isBlank()) {
                LOGGER.info("Skipping NPR source because NPR_API_KEY is not configured.");
                return CompletableFuture.completedFuture(new RssPollOutcome(sourceId, true, 0, 0));
            }
        }
        if (isNytSource(sourceId)) {
            String nytApiKey = System.getenv().getOrDefault("NYT_API_KEY", "").trim();
            if (nytApiKey.isBlank()) {
                LOGGER.info("Skipping NYT source because NYT_API_KEY is not configured.");
                return CompletableFuture.completedFuture(new RssPollOutcome(sourceId, true, 0, 0));
            }
            requestUrl = appendApiKey(source.url(), nytApiKey);
        }

        HttpRequest request = buildRequest(requestUrl, sourceId, ctx.requestTimeout());

        return sendWithRedirects(ctx, request, sourceId, 3)
                .orTimeout(ctx.requestTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .handle((response, error) -> {
                    if (error != null) {
                        LOGGER.warning(() -> "RSS fetch failed source=" + source.source() + " url=" + source.url()
                                + " reason=" + rootMessage(error));
                        ctx.eventBus().publish(new AlertRaised(
                                ctx.clock().instant(),
                                "collector",
                                "RSS fetch failed for " + source.source() + ": " + rootMessage(error),
                                Map.of("collector", name(), "source", source.source(), "url", source.url())
                        ));
                        return new RssPollOutcome(source.source(), false, 0, 0);
                    }

                    if (response.statusCode() == 401 || response.statusCode() == 403) {
                        String contentType = response.headers().firstValue("Content-Type").orElse("-");
                        LOGGER.warning(() -> "RSS access denied source=" + source.source()
                                + " status=" + response.statusCode()
                                + " contentType=" + contentType
                                + " url=" + sanitizeText(source.url()));
                        ctx.eventBus().publish(new AlertRaised(
                                ctx.clock().instant(),
                                "collector",
                                "RSS fetch failed for " + source.source() + ": HTTP " + response.statusCode(),
                                Map.of("collector", name(), "source", source.source(), "url", source.url(), "status", response.statusCode())
                        ));
                        return new RssPollOutcome(source.source(), false, 0, 0);
                    }

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        String contentType = response.headers().firstValue("Content-Type").orElse("-");
                        LOGGER.warning(() -> "RSS fetch failed source=" + source.source() + " url=" + sanitizeText(source.url())
                                + " status=" + response.statusCode()
                                + " contentType=" + contentType);
                        ctx.eventBus().publish(new AlertRaised(
                                ctx.clock().instant(),
                                "collector",
                                "RSS fetch failed for " + source.source() + ": HTTP " + response.statusCode(),
                                Map.of("collector", name(), "source", source.source(), "url", source.url(), "status", response.statusCode())
                        ));
                        return new RssPollOutcome(source.source(), false, 0, 0);
                    }

                    ParseOutcome parsed = parseStoriesOutcome(response.body(), sourceId);
                    if (parsed.invalidXml()) {
                        String bodySnippet = snippet(sanitizeText(response.body()), 200);
                        String bodyType = classifyBody(response.body());
                        String contentType = response.headers().firstValue("Content-Type").orElse("-");
                        String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("-");
                        LOGGER.warning(() -> "Invalid RSS/Atom XML source=" + source.source()
                                + " finalUrl=" + sanitizeText(response.uri().toString())
                                + " httpStatus=" + response.statusCode()
                                + " contentType=" + contentType
                                + " contentEncoding=" + contentEncoding
                                + " bodyStartsWith=" + bodyType
                                + " snippet=\"" + bodySnippet + "\"");
                        ctx.eventBus().publish(new AlertRaised(
                                ctx.clock().instant(),
                                "collector",
                                "Invalid RSS/Atom XML for source " + source.source(),
                                Map.of("collector", name(), "source", source.source(), "url", source.url())
                        ));
                        return new RssPollOutcome(sourceId, false, 0, 0);
                    }

                    List<NewsStory> stories = parsed.stories().stream()
                            .sorted(Comparator.comparing(NewsStory::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                            .limit(Math.max(1, cfg.topStories()))
                            .toList();

                    NewsSignal signal = new NewsSignal(sourceId, stories, ctx.clock().instant());
                    ctx.signalStore().putNews(signal);
                    ctx.eventBus().publish(new NewsUpdated(ctx.clock().instant(), sourceId, stories.size()));

                    List<String> loweredKeywords = cfg.keywords().stream()
                            .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                            .toList();
                    List<NewsStory> matched = stories.stream()
                            .filter(story -> loweredKeywords.stream().anyMatch(k -> story.title().toLowerCase(Locale.ROOT).contains(k)))
                            .toList();

                    if (!matched.isEmpty()) {
                        ctx.eventBus().publish(new AlertRaised(
                                ctx.clock().instant(),
                                "rss_keyword_match",
                                "RSS keyword match detected",
                                Map.of(
                                        "source", source.source(),
                                        "keywords", cfg.keywords(),
                                        "matches", matched.stream().map(NewsStory::title).toList()
                                )
                        ));
                    }
                    return new RssPollOutcome(sourceId, true, stories.size(), matched.size());
                });
    }

    private CompletableFuture<HttpResponse<String>> sendWithRedirects(
            CollectorContext ctx,
            HttpRequest request,
            String sourceId,
            int redirectsRemaining
    ) {
        return ctx.httpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int status = response.statusCode();
                    if (redirectsRemaining > 0 && isRedirect(status)) {
                        Optional<String> location = response.headers().firstValue("Location");
                        if (location.isPresent()) {
                            URI redirectUri = request.uri().resolve(location.get());
                            LOGGER.info(() -> "RSS redirect source=" + sourceId + " from="
                                    + sanitizeText(request.uri().toString()) + " to=" + sanitizeText(redirectUri.toString())
                                    + " status=" + status);
                            return sendWithRedirects(
                                    ctx,
                                    buildRequest(redirectUri.toString(), sourceId, ctx.requestTimeout()),
                                    sourceId,
                                    redirectsRemaining - 1
                            );
                        }
                    }
                    return CompletableFuture.completedFuture(response);
                })
                .exceptionallyCompose(error -> {
                    if (error.getCause() instanceof HttpTimeoutException) {
                        return CompletableFuture.failedFuture(error.getCause());
                    }
                    return CompletableFuture.failedFuture(error);
                });
    }

    private HttpRequest buildRequest(String url, String sourceId, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(timeout)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", acceptsFor(sourceId))
                .header("Accept-Encoding", "identity")
                .build();
    }

    private CollectorResult summarize(List<RssPollOutcome> outcomes) {
        long successes = outcomes.stream().filter(RssPollOutcome::success).count();
        long failures = outcomes.size() - successes;
        int stories = outcomes.stream().mapToInt(RssPollOutcome::storyCount).sum();
        int keywordMatches = outcomes.stream().mapToInt(RssPollOutcome::keywordMatches).sum();
        Map<String, Object> stats = new HashMap<>();
        stats.put("sources", outcomes.stream().map(RssPollOutcome::source).toList());
        stats.put("successes", successes);
        stats.put("failures", failures);
        stats.put("stories", stories);
        stats.put("keywordMatches", keywordMatches);
        if (successes == outcomes.size()) {
            return CollectorResult.success("RSS polling completed", stats);
        }
        if (successes > 0) {
            return CollectorResult.success("RSS polling partially completed", stats);
        }
        return CollectorResult.failure("RSS polling had failures", stats);
    }

    static List<NewsStory> parseStories(String xml, String source) {
        return parseStoriesOutcome(xml, source).stories();
    }

    private static ParseOutcome parseStoriesOutcome(String body, String source) {
        String sourceId = source.toLowerCase(Locale.ROOT);
        if (isNytSource(sourceId)) {
            return parseNytStories(body, source);
        }
        if ("npr".equals(sourceId)) {
            return parseNprStories(body, source);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);

            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                }

                @Override
                public void error(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }
            });

            String normalizedBody = normalizeXmlBody(body);
            Document document = builder.parse(new ByteArrayInputStream(normalizedBody.getBytes(StandardCharsets.UTF_8)));
            Element root = document.getDocumentElement();
            if (root == null) {
                return new ParseOutcome(List.of(), false);
            }
            String rootName = root.getTagName().toLowerCase(Locale.ROOT);
            if ("rss".equals(rootName)) {
                return new ParseOutcome(parseRss(document, source), false);
            }
            if ("feed".equals(rootName)) {
                return new ParseOutcome(parseAtom(document, source), false);
            }
            return new ParseOutcome(List.of(), false);
        } catch (Exception e) {
            return new ParseOutcome(List.of(), true);
        }
    }

    private static String normalizeXmlBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalized = body.replace("\uFEFF", "");
        int firstTag = normalized.indexOf('<');
        if (firstTag > 0) {
            normalized = normalized.substring(firstTag);
        }
        // Strip control characters that commonly break strict XML parsers in syndicated feeds.
        return normalized.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }

    private static List<NewsStory> parseRss(Document document, String source) {
        NodeList items = document.getElementsByTagName("item");
        List<NewsStory> stories = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Node item = items.item(i);
            String title = childText(item, "title").orElse("(untitled)");
            String link = childText(item, "link")
                    .or(() -> childText(item, "guid"))
                    .or(() -> childAttribute(item, "enclosure", "url"))
                    .orElse("");
            Instant publishedAt = parseDate(childText(item, "pubDate").orElse(null));
            stories.add(new NewsStory(title, link, publishedAt, source));
        }
        return stories;
    }

    private static List<NewsStory> parseAtom(Document document, String source) {
        NodeList entries = document.getElementsByTagName("entry");
        List<NewsStory> stories = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Node entry = entries.item(i);
            String title = childText(entry, "title").orElse("(untitled)");
            String link = childAttribute(entry, "link", "href").orElse("");
            Instant publishedAt = parseDate(childText(entry, "updated").orElseGet(() -> childText(entry, "published").orElse(null)));
            stories.add(new NewsStory(title, link, publishedAt, source));
        }
        return stories;
    }

    private static Optional<String> childText(Node parent, String tagName) {
        if (!(parent instanceof Element element)) {
            return Optional.empty();
        }
        NodeList children = element.getElementsByTagName(tagName);
        if (children.getLength() == 0) {
            return Optional.empty();
        }
        String text = children.item(0).getTextContent();
        return text == null ? Optional.empty() : Optional.of(text.trim());
    }

    private static Optional<String> childAttribute(Node parent, String tagName, String attribute) {
        if (!(parent instanceof Element element)) {
            return Optional.empty();
        }
        NodeList children = element.getElementsByTagName(tagName);
        if (children.getLength() == 0) {
            return Optional.empty();
        }
        Node node = children.item(0);
        if (!(node instanceof Element child)) {
            return Optional.empty();
        }
        String value = child.getAttribute(attribute);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static Instant parseDate(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        List<java.util.function.Function<String, Instant>> parsers = List.of(
                v -> Instant.parse(v),
                v -> OffsetDateTime.parse(v).toInstant(),
                v -> ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        );
        return parsers.stream()
                .map(parser -> safelyParse(parser, value))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElse(Instant.EPOCH);
    }

    private static Optional<Instant> safelyParse(java.util.function.Function<String, Instant> parser, String value) {
        try {
            return Optional.of(parser.apply(value));
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    private static ParseOutcome parseNytStories(String json, String source) {
        try {
            var root = JsonUtils.objectMapper().readTree(json);
            var results = root.path("results");
            if (!results.isArray()) {
                return new ParseOutcome(List.of(), false);
            }
            List<NewsStory> stories = new ArrayList<>();
            for (var item : results) {
                String title = item.path("title").asText("(untitled)");
                String link = item.path("url").asText("");
                Instant publishedAt = parseDate(item.path("published_date").asText(null));
                String summary = item.path("abstract").asText("");
                if (!summary.isBlank() && !title.contains(" - ")) {
                    title = title + " - " + summary;
                }
                stories.add(new NewsStory(title, link, publishedAt, source));
            }
            return new ParseOutcome(stories, false);
        } catch (Exception e) {
            return new ParseOutcome(List.of(), true);
        }
    }

    private static ParseOutcome parseNprStories(String json, String source) {
        try {
            var root = JsonUtils.objectMapper().readTree(json);
            var items = root.path("items");
            if (!items.isArray()) {
                return new ParseOutcome(List.of(), false);
            }
            List<NewsStory> stories = new ArrayList<>();
            for (var item : items) {
                String title = item.path("title").asText("(untitled)");
                String link = item.path("url").asText("");
                Instant publishedAt = parseDate(item.path("publishedAt").asText(null));
                stories.add(new NewsStory(title, link, publishedAt, source));
            }
            return new ParseOutcome(stories, false);
        } catch (Exception e) {
            return new ParseOutcome(List.of(), true);
        }
    }

    private static String acceptsFor(String sourceId) {
        if (isNytSource(sourceId) || "npr".equalsIgnoreCase(sourceId)) {
            return "application/json";
        }
        return "application/rss+xml, application/atom+xml, application/xml, text/xml, */*";
    }

    private static boolean isNytSource(String sourceId) {
        if (sourceId == null) {
            return false;
        }
        return sourceId.toLowerCase(Locale.ROOT).startsWith("nyt");
    }

    private static String appendApiKey(String baseUrl, String apiKey) {
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "api-key=" + apiKey;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 307 || statusCode == 308;
    }

    private static String classifyBody(String body) {
        if (body == null) {
            return "OTHER";
        }
        String normalized = body.stripLeading().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("<rss")) {
            return "RSS";
        }
        if (normalized.startsWith("<feed")) {
            return "ATOM";
        }
        if (normalized.startsWith("<!doctype html") || normalized.startsWith("<html")) {
            return "HTML";
        }
        return "OTHER";
    }

    private static String sanitizeText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)([?&](?:api[-_]?key|api_key|key|token)=)[^&\\s]+", "$1***")
                .replaceAll("(?i)(authorization:)[^\\r\\n]+", "$1 ***");
    }

    private static String snippet(String value, int max) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.length() <= max) {
            return compact;
        }
        return compact.substring(0, max);
    }

    private record ParseOutcome(List<NewsStory> stories, boolean invalidXml) {
    }

    private record RssPollOutcome(String source, boolean success, int storyCount, int keywordMatches) {
    }
}

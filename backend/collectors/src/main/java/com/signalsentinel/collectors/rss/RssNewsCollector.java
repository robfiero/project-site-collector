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

public class RssNewsCollector implements Collector {
    public static final String CONFIG_KEY = "rssCollector";
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(source.url()))
                .GET()
                .timeout(ctx.requestTimeout())
                .build();

        return ctx.httpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(ctx.requestTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .handle((response, error) -> {
                    if (error != null) {
                        ctx.eventBus().publish(new AlertRaised(
                                ctx.clock().instant(),
                                "collector",
                                "RSS fetch failed for " + source.source() + ": " + rootMessage(error),
                                Map.of("collector", name(), "source", source.source(), "url", source.url())
                        ));
                        return new RssPollOutcome(source.source(), false, 0, 0);
                    }

                    ParseOutcome parsed = parseStoriesOutcome(response.body(), source.source());
                    if (parsed.invalidXml()) {
                        ctx.eventBus().publish(new AlertRaised(
                                ctx.clock().instant(),
                                "collector",
                                "Invalid RSS/Atom XML for source " + source.source(),
                                Map.of("collector", name(), "source", source.source(), "url", source.url())
                        ));
                        return new RssPollOutcome(source.source(), false, 0, 0);
                    }

                    List<NewsStory> stories = parsed.stories().stream()
                            .sorted(Comparator.comparing(NewsStory::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                            .limit(Math.max(1, cfg.topStories()))
                            .toList();

                    NewsSignal signal = new NewsSignal(source.source(), stories, ctx.clock().instant());
                    ctx.signalStore().putNews(signal);
                    ctx.eventBus().publish(new NewsUpdated(ctx.clock().instant(), source.source(), stories.size()));

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
                    return new RssPollOutcome(source.source(), true, stories.size(), matched.size());
                });
    }

    private CollectorResult summarize(List<RssPollOutcome> outcomes) {
        long successes = outcomes.stream().filter(RssPollOutcome::success).count();
        int stories = outcomes.stream().mapToInt(RssPollOutcome::storyCount).sum();
        int keywordMatches = outcomes.stream().mapToInt(RssPollOutcome::keywordMatches).sum();
        Map<String, Object> stats = new HashMap<>();
        stats.put("sources", outcomes.stream().map(RssPollOutcome::source).toList());
        stats.put("successes", successes);
        stats.put("stories", stories);
        stats.put("keywordMatches", keywordMatches);
        if (successes == outcomes.size()) {
            return CollectorResult.success("RSS polling completed", stats);
        }
        return CollectorResult.failure("RSS polling had failures", stats);
    }

    static List<NewsStory> parseStories(String xml, String source) {
        return parseStoriesOutcome(xml, source).stories();
    }

    private static ParseOutcome parseStoriesOutcome(String xml, String source) {
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

            Document document = builder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))
            );
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

    private static List<NewsStory> parseRss(Document document, String source) {
        NodeList items = document.getElementsByTagName("item");
        List<NewsStory> stories = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Node item = items.item(i);
            String title = childText(item, "title").orElse("(untitled)");
            String link = childText(item, "link").orElse("");
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

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private record ParseOutcome(List<NewsStory> stories, boolean invalidXml) {
    }

    private record RssPollOutcome(String source, boolean success, int storyCount, int keywordMatches) {
    }
}

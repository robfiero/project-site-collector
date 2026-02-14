package com.signalsentinel.core.model;

import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import com.signalsentinel.core.events.ContentChanged;
import com.signalsentinel.core.events.NewsUpdated;
import com.signalsentinel.core.events.SiteFetched;
import com.signalsentinel.core.events.WeatherUpdated;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreModelsAndEventsTest {
    @Test
    void modelsExposeExpectedState() {
        Instant now = Instant.parse("2026-02-01T00:00:00Z");
        SiteConfig siteConfig = new SiteConfig("site-1", "https://example.com", List.of("tech"), ParseMode.TITLE);
        CollectorConfig collectorConfig = new CollectorConfig("site", true, 30, Map.of("timeoutMillis", 500));
        SiteSignal siteSignal = new SiteSignal("site-1", "https://example.com", "abc", "Example", 2, now, now);
        NewsStory story = new NewsStory("Story", "https://example.com/story", now, "feed");
        NewsSignal newsSignal = new NewsSignal("feed", List.of(story), now);
        WeatherSignal weatherSignal = new WeatherSignal("Austin,TX", 71.5, "Clear", List.of(), now);
        SignalSnapshot snapshot = new SignalSnapshot(
                Map.of(siteSignal.siteId(), siteSignal),
                Map.of(newsSignal.source(), newsSignal),
                Map.of(weatherSignal.location(), weatherSignal)
        );

        assertEquals("site-1", siteConfig.id());
        assertEquals(ParseMode.TITLE, siteConfig.parseMode());
        assertTrue(collectorConfig.enabled());
        assertEquals(30, collectorConfig.intervalSeconds());
        assertEquals("Example", snapshot.siteSignals().get("site-1").title());
        assertEquals("Story", snapshot.newsSignals().get("feed").stories().getFirst().title());
        assertEquals("Clear", snapshot.weatherSignals().get("Austin,TX").conditions());
        assertEquals(ParseMode.LINKS, ParseMode.valueOf("LINKS"));
        assertEquals(3, ParseMode.values().length);
    }

    @Test
    void eventsExposeTypeAndPayload() {
        Instant now = Instant.parse("2026-02-01T00:00:00Z");

        CollectorTickStarted started = new CollectorTickStarted(now, "siteCollector");
        CollectorTickCompleted completed = new CollectorTickCompleted(now, "siteCollector", true, 100);
        SiteFetched fetched = new SiteFetched(now, "site-1", "https://example.com", 200, 42);
        ContentChanged changed = new ContentChanged(now, "site-1", "https://example.com", "old", "new");
        NewsUpdated news = new NewsUpdated(now, "world", 5);
        WeatherUpdated weather = new WeatherUpdated(now, "Austin,TX", 72.0, "Sunny");
        AlertRaised alert = new AlertRaised(now, "collector", "timeout", Map.of("siteId", "site-1"));

        assertEquals("CollectorTickStarted", started.type());
        assertEquals("CollectorTickCompleted", completed.type());
        assertEquals("SiteFetched", fetched.type());
        assertEquals("ContentChanged", changed.type());
        assertEquals("NewsUpdated", news.type());
        assertEquals("WeatherUpdated", weather.type());
        assertEquals("AlertRaised", alert.type());
        assertEquals("siteCollector", started.collectorName());
        assertEquals("new", changed.newHash());
        assertEquals(200, fetched.status());
    }
}

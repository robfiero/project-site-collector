package com.signalsentinel.core.model;

import com.signalsentinel.core.events.EnvAqiUpdated;
import com.signalsentinel.core.events.EnvWeatherUpdated;
import com.signalsentinel.core.events.LocalHappeningsIngested;
import com.signalsentinel.core.events.LoginFailed;
import com.signalsentinel.core.events.LoginSucceeded;
import com.signalsentinel.core.events.NewsItemsIngested;
import com.signalsentinel.core.events.PasswordResetFailed;
import com.signalsentinel.core.events.PasswordResetRequested;
import com.signalsentinel.core.events.PasswordResetSucceeded;
import com.signalsentinel.core.events.UserRegistered;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdditionalModelsAndEventsTest {
    @Test
    void eventsExposeTypeAndFields() {
        Instant now = Instant.parse("2026-03-01T00:00:00Z");

        LoginSucceeded loginSucceeded = new LoginSucceeded(now, "user-1", "user@example.com");
        LoginFailed loginFailed = new LoginFailed(now, "user@example.com", "bad password");
        PasswordResetRequested resetRequested = new PasswordResetRequested(now, "user@example.com");
        PasswordResetSucceeded resetSucceeded = new PasswordResetSucceeded(now, "user-1", "user@example.com");
        PasswordResetFailed resetFailed = new PasswordResetFailed(now, "user@example.com", "expired token");
        UserRegistered userRegistered = new UserRegistered(now, "user-1", "user@example.com");
        NewsItemsIngested newsItems = new NewsItemsIngested(now, "rss", 5);
        LocalHappeningsIngested localEvents = new LocalHappeningsIngested(now, "ticketmaster", "02108", 3);
        EnvWeatherUpdated envWeather = new EnvWeatherUpdated(now, "02108", "Boston, MA", 42.3, -71.0, 60.1, "Clear", "WeatherAPI", 0L, "ok", null, null, null);
        EnvAqiUpdated envAqi = new EnvAqiUpdated(now, "02108", "Boston, MA", 42.3, -71.0, 55, "Moderate", "ok", "AirNow", 0L, "ok", null, null, null);

        assertEquals("LoginSucceeded", loginSucceeded.type());
        assertEquals("LoginFailed", loginFailed.type());
        assertEquals("PasswordResetRequested", resetRequested.type());
        assertEquals("PasswordResetSucceeded", resetSucceeded.type());
        assertEquals("PasswordResetFailed", resetFailed.type());
        assertEquals("UserRegistered", userRegistered.type());
        assertEquals("NewsItemsIngested", newsItems.type());
        assertEquals("LocalHappeningsIngested", localEvents.type());
        assertEquals("EnvWeatherUpdated", envWeather.type());
        assertEquals("EnvAqiUpdated", envAqi.type());
        assertEquals("user-1", loginSucceeded.userId());
        assertEquals("user@example.com", loginFailed.email());
        assertEquals(5, newsItems.count());
        assertEquals(3, localEvents.itemCount());
        assertEquals("02108", envAqi.zip());
    }

    @Test
    void modelsExposeExpectedState() {
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        HappeningItem item = new HappeningItem(
                "event-1",
                "Jazz Night",
                "2026-03-12T20:00:00Z",
                "Venue A",
                "Boston",
                "MA",
                "https://example.com/event",
                "music",
                "ticketmaster"
        );
        LocalHappeningsSignal happenings = new LocalHappeningsSignal("02108", List.of(item), "Powered by Ticketmaster", now);
        AirQualitySignal airQuality = new AirQualitySignal("02108", 55, "Moderate", now);
        MarketQuoteSignal market = new MarketQuoteSignal("AAPL", 189.5, 1.25, now);
        WeatherSignal weather = new WeatherSignal("Boston, MA", 60.0, "Clear", List.of("Sunny"), now);
        SignalSnapshot snapshot = new SignalSnapshot(
                Map.of(),
                Map.of(),
                Map.of(weather.location(), weather)
        );

        assertEquals("02108", happenings.location());
        assertEquals("event-1", happenings.items().getFirst().id());
        assertEquals(55, airQuality.aqi());
        assertEquals("AAPL", market.symbol());
        assertTrue(snapshot.weatherSignals().containsKey("Boston, MA"));
    }
}

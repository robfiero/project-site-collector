package com.signalsentinel.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.LocalHappeningsIngested;
import com.signalsentinel.core.events.NewsItemsIngested;
import com.signalsentinel.core.events.NewsUpdated;
import com.signalsentinel.core.model.HappeningItem;
import com.signalsentinel.core.model.LocalHappeningsSignal;
import com.signalsentinel.core.util.HashingUtils;
import com.signalsentinel.core.util.JsonUtils;
import com.signalsentinel.service.auth.AuthService;
import com.signalsentinel.service.auth.JwtService;
import com.signalsentinel.service.auth.PasswordHashing;
import com.signalsentinel.service.auth.PasswordResetStore;
import com.signalsentinel.service.auth.PasswordResetTokenRecord;
import com.signalsentinel.service.auth.PreferencesStore;
import com.signalsentinel.service.auth.ResetTokenService;
import com.signalsentinel.service.auth.UserStore;
import com.signalsentinel.service.email.DevOutboxEmailSender;
import com.signalsentinel.service.email.EmailMessage;
import com.signalsentinel.service.store.EventCodec;
import com.signalsentinel.service.store.JsonFileSignalStore;
import com.signalsentinel.service.store.JsonlEventStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthApiIntegrationTest {
    private ApiServer apiServer;

    @AfterEach
    void tearDown() {
        if (apiServer != null) {
            apiServer.stop();
        }
    }

    @Test
    void signupLoginLogoutSetAndClearCookie() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "user@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, signup.statusCode());
        String authCookie = cookieFrom(signup);
        assertNotNull(authCookie);

        HttpResponse<String> me = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me"))
                        .header("Cookie", authCookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, me.statusCode());
        assertTrue(me.body().contains("user@example.com"));

        HttpResponse<String> login = client.send(jsonPost(runtime.uri("/api/auth/login"), Map.of(
                "email", "user@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
        assertTrue(cookieFrom(login).startsWith("signal_sentinel_auth="));
        waitForRefreshCount(runtime.refreshCount(), 1);

        HttpResponse<String> logout = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/auth/logout"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, logout.statusCode());
        assertTrue(logout.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));
        waitForRefreshCount(runtime.refreshCount(), 2);
    }

    @Test
    void accountDeleteRemovesUserAndPreferences() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "delete@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, signup.statusCode());
        String authCookie = cookieFrom(signup);

        HttpResponse<String> delete = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/delete"))
                        .header("Cookie", authCookie)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, delete.statusCode());
        assertTrue(runtime.userStore().findByEmail("delete@example.com").isEmpty());
        assertTrue(runtime.preferencesStore().all().isEmpty());

        HttpResponse<String> me = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me"))
                        .header("Cookie", authCookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(me.statusCode() == 401 || me.statusCode() == 404);
    }

    @Test
    void forgotAlwaysReturnsOkButOnlyKnownUserCreatesOutboxMessage() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "known@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> unknown = client.send(jsonPost(runtime.uri("/api/auth/forgot"), Map.of(
                "email", "missing@example.com"
        )), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, unknown.statusCode());

        HttpResponse<String> outboxAfterUnknown = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/dev/outbox")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, outboxAfterUnknown.statusCode());
        assertEquals(0, JsonUtils.objectMapper().readTree(outboxAfterUnknown.body()).size());

        HttpResponse<String> known = client.send(jsonPost(runtime.uri("/api/auth/forgot"), Map.of(
                "email", "known@example.com"
        )), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, known.statusCode());

        HttpResponse<String> outboxAfterKnown = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/dev/outbox")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        JsonNode outbox = JsonUtils.objectMapper().readTree(outboxAfterKnown.body());
        assertEquals(1, outbox.size());
        assertTrue(outbox.get(0).get("subject").asText().toLowerCase().contains("password reset"));
        assertEquals("kn***@example.com", outbox.get(0).get("to").asText());
    }

    @Test
    void resetEnforcesExpiryAndOneTimeUse() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "reset@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());

        runtime.passwordResetStore().save(new PasswordResetTokenRecord(
                HashingUtils.sha256("expired-token"),
                runtime.userStore().findByEmail("reset@example.com").orElseThrow().id(),
                Instant.parse("2020-01-01T00:00:00Z"),
                false
        ));
        HttpResponse<String> expired = client.send(jsonPost(runtime.uri("/api/auth/reset"), Map.of(
                "token", "expired-token",
                "newPassword", "new-password-123"
        )), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, expired.statusCode());

        client.send(jsonPost(runtime.uri("/api/auth/forgot"), Map.of(
                "email", "reset@example.com"
        )), HttpResponse.BodyHandlers.ofString());
        String token = tokenFromOutbox(runtime);
        HttpResponse<String> first = client.send(jsonPost(runtime.uri("/api/auth/reset"), Map.of(
                "token", token,
                "newPassword", "new-password-123"
        )), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode());

        HttpResponse<String> second = client.send(jsonPost(runtime.uri("/api/auth/reset"), Map.of(
                "token", token,
                "newPassword", "another-password-123"
        )), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, second.statusCode());
    }

    @Test
    void preferencesRequireAuthAndCanBeUpdatedWhenLoggedIn() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "pref@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        HttpResponse<String> anonymous = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(401, anonymous.statusCode());

        HttpResponse<String> put = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Cookie", cookie)
                        .PUT(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of(
                                "userId", "ignored",
                                "zipCodes", List.of("02108", "98101"),
                                "watchlist", List.of("AAPL"),
                                "newsSourceIds", List.of("world"),
                                "themeMode", "dark",
                                "accent", "default"
                        ))))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, put.statusCode());
        assertTrue(put.body().contains("02108"));

        HttpResponse<String> get = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Cookie", cookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, get.statusCode());
        assertTrue(get.body().contains("AAPL"));
    }

    @Test
    void preferencesEndpointReturns401ForInvalidAuthCookie() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Cookie", "signal_sentinel_auth=not-a-valid-token")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("unauthorized"));
    }

    @Test
    void preferencesInvalidThemeOrAccentFallsBackToDefaults() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "prefs-theme@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        HttpResponse<String> put = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of(
                                "zipCodes", List.of("02108"),
                                "watchlist", List.of("AAPL"),
                                "newsSourceIds", List.of("cnn"),
                                "themeMode", "solarized",
                                "accent", "orange"
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, put.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(put.body());
        assertEquals("light", body.get("themeMode").asText());
        assertEquals("blue", body.get("accent").asText());
    }

    @Test
    void preferencesPutInvalidJsonClosesConnectionDeterministically() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "invalid-json-pref@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        IOException exception = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"zipCodes\":["))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        ));
        assertTrue(exception.getMessage().contains("header parser received no bytes"));
    }

    @Test
    void preferencesPutMissingFieldsClosesConnectionDeterministically() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "missing-fields-pref@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        IOException exception = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of("zipCodes", List.of("02108")))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        ));
        assertTrue(exception.getMessage().contains("header parser received no bytes"));
    }

    @Test
    void settingsResetUiScopeKeepsCollectorPreferencesUnchanged() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "reset-ui@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        HttpResponse<String> seeded = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of(
                                "zipCodes", List.of("60601"),
                                "watchlist", List.of("TSLA"),
                                "newsSourceIds", List.of("wsj"),
                                "themeMode", "light",
                                "accent", "gold"
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, seeded.statusCode());

        HttpResponse<String> reset = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/settings/reset"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of("scope", "ui"))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reset.statusCode());
        JsonNode resetBody = JsonUtils.objectMapper().readTree(reset.body());
        assertEquals("ui", resetBody.get("scopeApplied").asText());
        assertEquals("60601", resetBody.get("preferences").get("zipCodes").get(0).asText());
        assertEquals("TSLA", resetBody.get("preferences").get("watchlist").get(0).asText());
        assertEquals("wsj", resetBody.get("preferences").get("newsSourceIds").get(0).asText());
        assertEquals("light", resetBody.get("preferences").get("themeMode").asText());
        assertEquals("blue", resetBody.get("preferences").get("accent").asText());
    }

    @Test
    void settingsResetCollectorsScopeRestoresCatalogDefaultPreferences() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "reset-collectors@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of(
                                "zipCodes", List.of("60601"),
                                "watchlist", List.of("TSLA"),
                                "newsSourceIds", List.of("wsj"),
                                "themeMode", "light",
                                "accent", "gold"
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        HttpResponse<String> reset = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/settings/reset"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of("scope", "collectors"))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reset.statusCode());
        JsonNode resetBody = JsonUtils.objectMapper().readTree(reset.body());
        assertEquals("collectors", resetBody.get("scopeApplied").asText());
        assertEquals(List.of("02108", "98101"),
                JsonUtils.objectMapper().convertValue(resetBody.get("preferences").get("zipCodes"), List.class));
        assertEquals(List.of("AAPL", "MSFT", "SPY", "BTC-USD", "ETH-USD"),
                JsonUtils.objectMapper().convertValue(resetBody.get("preferences").get("watchlist"), List.class));
        assertEquals(List.of("cnn"),
                JsonUtils.objectMapper().convertValue(resetBody.get("preferences").get("newsSourceIds"), List.class));
        assertEquals("light", resetBody.get("preferences").get("themeMode").asText());
        assertEquals("gold", resetBody.get("preferences").get("accent").asText());
    }

    @Test
    void settingsResetAllScopeMatchesCollectorsScopeBehavior() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "reset-all@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of(
                                "zipCodes", List.of("60601"),
                                "watchlist", List.of("TSLA"),
                                "newsSourceIds", List.of("wsj"),
                                "themeMode", "light",
                                "accent", "gold"
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        HttpResponse<String> reset = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/settings/reset"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of("scope", "all"))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reset.statusCode());
        JsonNode resetBody = JsonUtils.objectMapper().readTree(reset.body());
        assertEquals("all", resetBody.get("scopeApplied").asText());
        assertTrue(resetBody.get("preferences").get("zipCodes").isArray());
        assertEquals("02108", resetBody.get("preferences").get("zipCodes").get(0).asText());
        assertEquals("light", resetBody.get("preferences").get("themeMode").asText());
        assertEquals("blue", resetBody.get("preferences").get("accent").asText());
    }

    @Test
    void settingsResetRejectsInvalidScopeWith400() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "reset-invalid@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/settings/reset"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of("scope", "oops"))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("scope must be one of"));
        assertFalse(response.body().contains("Exception"));
    }

    @Test
    void devOutboxEndpointRespectsFlag() throws Exception {
        TestRuntime runtime = startRuntime(false);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/dev/outbox")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(404, response.statusCode());
    }

    @Test
    void newsSourceSettingsCanBeReadAnonymouslyAndUpdatedWhenLoggedIn() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> anonymousGet = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/settings/newsSources")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, anonymousGet.statusCode());
        assertTrue(anonymousGet.body().contains("cnn"));

        HttpResponse<String> anonymousPut = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/settings/newsSources"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of(
                                "selectedSources", List.of("cnn")
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(401, anonymousPut.statusCode());

        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "news-pref@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        HttpResponse<String> authenticatedPut = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/settings/newsSources"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of(
                                "selectedSources", List.of("cnn", "wsj")
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, authenticatedPut.statusCode());
        assertTrue(authenticatedPut.body().contains("cnn"));

        HttpResponse<String> getPrefs = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Cookie", cookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, getPrefs.statusCode());
        assertTrue(getPrefs.body().contains("cnn"));
        assertTrue(getPrefs.body().contains("wsj"));
    }

    @Test
    void newsSourceSettingsPutWithInvalidSelectedSourcesTypeFallsBackToDefaults() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "settings-type@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/settings/newsSources"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"selectedSources\":\"cnn\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("effectiveSelectedSources"));
        assertTrue(response.body().contains("cnn"));
    }

    @Test
    void newsSourceSettingsPutIgnoresNonStringEntries() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "settings-non-string@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/settings/newsSources"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"selectedSources\":[\"cnn\",123,true]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("cnn"));
        assertTrue(!response.body().contains("123"));
    }

    @Test
    void signalsEndpointFiltersLocalHappeningsByAuthenticatedUserZipPreferences() throws Exception {
        TestRuntime runtime = startRuntime(true);
        runtime.signalStore().putLocalHappenings(new LocalHappeningsSignal(
                "02108",
                List.of(new HappeningItem("evt-1", "Boston", "2026-03-01T20:00:00Z", "Venue", "Boston", "MA", "https://example.com/1", "Music", "ticketmaster")),
                "Powered by Ticketmaster",
                Instant.parse("2026-02-20T10:00:00Z")
        ));
        runtime.signalStore().putLocalHappenings(new LocalHappeningsSignal(
                "32830",
                List.of(new HappeningItem("evt-2", "Orlando", "2026-03-01T20:00:00Z", "Venue", "Orlando", "FL", "https://example.com/2", "Music", "ticketmaster")),
                "Powered by Ticketmaster",
                Instant.parse("2026-02-20T10:00:00Z")
        ));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "zip-pref@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of(
                                "zipCodes", List.of("32830"),
                                "watchlist", List.of("AAPL"),
                                "newsSourceIds", List.of("cnn")
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        HttpResponse<String> authedSignals = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals"))
                        .header("Cookie", cookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        JsonNode authedBody = JsonUtils.objectMapper().readTree(authedSignals.body());
        assertTrue(authedBody.get("localHappenings").has("32830"));
        assertTrue(!authedBody.get("localHappenings").has("02108"));

        HttpResponse<String> anonSignals = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        JsonNode anonBody = JsonUtils.objectMapper().readTree(anonSignals.body());
        assertTrue(anonBody.get("localHappenings").has("02108"));
        assertTrue(!anonBody.get("localHappenings").has("32830"));
    }

    @Test
    void adminTrendsRequiresAuthenticationAndReturnsSnapshotWhenAuthenticated() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> anonymous = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/admin/trends")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(401, anonymous.statusCode());

        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "admin-trends@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        Instant ts = Instant.now();
        runtime.eventBus().publish(new CollectorTickCompleted(ts, "rssCollector", true, 25));
        runtime.eventBus().publish(new NewsUpdated(ts, "cnn", 3));
        runtime.eventBus().publish(new NewsItemsIngested(ts, "cnn", 3));
        runtime.eventBus().publish(new LocalHappeningsIngested(ts, "02108", "ticketmaster", 2));

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/admin/trends"))
                        .header("Cookie", cookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertTrue(body.has("windowStart"));
        assertTrue(body.has("asOf"));
        assertEquals(300, body.get("bucketSeconds").asInt());
        assertTrue(body.get("series").isArray());
        assertTrue(body.get("series").toString().contains("collector.runs.rssCollector.success"));
        assertTrue(body.get("series").toString().contains("ingested.news.cnn"));
        assertTrue(body.get("series").toString().contains("ingested.localEvents."));
    }

    @Test
    void adminEndpointsReturnAuthDisabledWhenAuthFeatureOff() throws Exception {
        TestRuntime runtime = startRuntime(true, false);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> trends = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/admin/trends")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(404, trends.statusCode());
        assertTrue(trends.body().contains("auth_disabled"));

        HttpResponse<String> preview = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/admin/email/preview")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(404, preview.statusCode());
        assertTrue(preview.body().contains("auth_disabled"));
    }

    @Test
    void adminEmailPreviewReturnsDeterministicIncludedCountsForEffectivePrefs() throws Exception {
        TestRuntime runtime = startRuntime(true);
        runtime.signalStore().putNews(new com.signalsentinel.core.model.NewsSignal(
                "cnn",
                List.of(
                        new com.signalsentinel.core.model.NewsStory("CNN A", "https://example.com/a", Instant.parse("2026-02-25T10:00:00Z"), "cnn"),
                        new com.signalsentinel.core.model.NewsStory("CNN B", "https://example.com/b", Instant.parse("2026-02-25T11:00:00Z"), "cnn")
                ),
                Instant.parse("2026-02-25T12:00:00Z")
        ));
        runtime.signalStore().putNews(new com.signalsentinel.core.model.NewsSignal(
                "abc",
                List.of(new com.signalsentinel.core.model.NewsStory("ABC A", "https://example.com/c", Instant.parse("2026-02-25T10:00:00Z"), "abc")),
                Instant.parse("2026-02-25T12:00:00Z")
        ));
        runtime.signalStore().putLocalHappenings(new LocalHappeningsSignal(
                "02108",
                List.of(
                        new HappeningItem("evt-1", "Concert", "2026-03-01T20:00:00Z", "Venue", "Boston", "MA", "https://example.com/1", "Music", "ticketmaster"),
                        new HappeningItem("evt-2", "Show", "2026-03-02T20:00:00Z", "Venue", "Boston", "MA", "https://example.com/2", "Arts", "ticketmaster")
                ),
                "Powered by Ticketmaster",
                Instant.parse("2026-02-25T12:00:00Z")
        ));
        runtime.signalStore().putLocalHappenings(new LocalHappeningsSignal(
                "98101",
                List.of(new HappeningItem("evt-3", "Game", "2026-03-03T20:00:00Z", "Arena", "Seattle", "WA", "https://example.com/3", "Sports", "ticketmaster")),
                "Powered by Ticketmaster",
                Instant.parse("2026-02-25T12:00:00Z")
        ));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "admin-preview@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(Map.of(
                                "zipCodes", List.of("98101"),
                                "watchlist", List.of("AAPL"),
                                "newsSourceIds", List.of("cnn"),
                                "themeMode", "dark",
                                "accent", "default"
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/admin/email/preview"))
                        .header("Cookie", cookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertEquals("dev_outbox", body.get("mode").asText());
        assertEquals(2, body.get("includedCounts").get("newsStories").asInt());
        assertEquals(1, body.get("includedCounts").get("localEvents").asInt());
        assertTrue(body.get("subject").asText().contains("Today's Overview Digest Preview"));
        assertTrue(body.get("body").asText().contains("News stories included: 2"));
        assertEquals("", body.get("lastSentAt").asText());
        assertEquals("", body.get("lastError").asText());
    }

    @Test
    void adminEmailPreviewReturns401WhenUnauthenticated() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/admin/email/preview")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("unauthorized"));
    }

    @Test
    void adminEmailPreviewEmptyStoresReturnsZeroCountsAndNoNullFields() throws Exception {
        TestRuntime runtime = startRuntime(true);
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> signup = client.send(jsonPost(runtime.uri("/api/auth/signup"), Map.of(
                "email", "admin-preview-empty@example.com",
                "password", "password-123"
        )), HttpResponse.BodyHandlers.ofString());
        String cookie = cookieFrom(signup);

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/admin/email/preview"))
                        .header("Cookie", cookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertEquals(0, body.get("includedCounts").get("sites").asInt());
        assertEquals(0, body.get("includedCounts").get("newsStories").asInt());
        assertEquals(0, body.get("includedCounts").get("localEvents").asInt());
        assertEquals(0, body.get("includedCounts").get("weather").asInt());
        assertEquals(0, body.get("includedCounts").get("markets").asInt());
        assertEquals("", body.get("body").asText());
        assertEquals("", body.get("lastSentAt").asText());
        assertEquals("", body.get("lastError").asText());
    }

    private TestRuntime startRuntime(boolean devOutboxEnabled) throws Exception {
        return startRuntime(devOutboxEnabled, true);
    }

    private TestRuntime startRuntime(boolean devOutboxEnabled, boolean authEnabled) throws Exception {
        Path tempDir = Files.createTempDirectory("signal-sentinel-auth-it-");
        JsonFileSignalStore signalStore = new JsonFileSignalStore(tempDir.resolve("state/signals.json"));
        JsonlEventStore eventStore = new JsonlEventStore(tempDir.resolve("logs/events.jsonl"));
        EventBus eventBus = new EventBus((event, error) -> {
            throw new AssertionError("EventBus handler error", error);
        });
        EventCodec.subscribeAll(eventBus, eventStore::append);

        Clock clock = Clock.systemUTC();
        UserStore userStore = new UserStore(tempDir.resolve("data/users.json"));
        PreferencesStore preferencesStore = new PreferencesStore(tempDir.resolve("data/preferences.json"));
        PasswordResetStore passwordResetStore = new PasswordResetStore(tempDir.resolve("data/password_resets.json"));
        DevOutboxEmailSender devOutbox = new DevOutboxEmailSender(tempDir.resolve("data/outbox.json"));
        AuthService authService = authEnabled
                ? new AuthService(
                        userStore,
                        preferencesStore,
                        passwordResetStore,
                        new TestPasswordHasher(),
                        new JwtService("test-secret", clock, Duration.ofHours(8)),
                        new ResetTokenService(),
                        devOutbox,
                        eventBus,
                        clock,
                        "http://localhost:5173"
                )
                : null;
        SseBroadcaster broadcaster = new SseBroadcaster(eventBus);
        DiagnosticsTracker diagnosticsTracker = new DiagnosticsTracker(eventBus, clock, () -> 0);
        apiServer = new ApiServer(
                0,
                signalStore,
                eventStore,
                broadcaster,
                List.of(),
                diagnosticsTracker,
                Map.of(
                        "defaultZipCodes", List.of("02108", "98101"),
                        "defaultWatchlist", List.of("AAPL", "MSFT", "SPY", "BTC-USD", "ETH-USD"),
                        "defaultNewsSources", List.of(
                                Map.of("id", "cnn", "name", "CNN", "type", "rss", "url", "https://example.com/cnn", "enabledByDefault", true, "requiresConfig", false, "note", ""),
                                Map.of("id", "abc", "name", "ABC News", "type", "rss", "url", "https://feeds.abcnews.com/abcnews/topstories", "enabledByDefault", false, "requiresConfig", false, "note", ""),
                                Map.of("id", "wsj", "name", "WSJ", "type", "rss", "url", "https://example.com/wsj", "enabledByDefault", true, "requiresConfig", false, "note", "")
                        ),
                        "defaultSelectedNewsSources", List.of("cnn")
                ),
                Map.of(),
                authService,
                false,
                devOutboxEnabled,
                devOutboxEnabled ? devOutbox : null
        );
        AtomicInteger refreshCount = new AtomicInteger();
        apiServer.setCollectorRefreshHook(collectors -> refreshCount.incrementAndGet());
        apiServer.start();
        return new TestRuntime(
                apiServer.actualPort(),
                userStore,
                passwordResetStore,
                preferencesStore,
                signalStore,
                refreshCount,
                eventBus,
                devOutboxEnabled ? devOutbox : null
        );
    }

    private static void waitForRefreshCount(AtomicInteger counter, int expectedAtLeast) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1500;
        while (counter.get() < expectedAtLeast && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private static HttpRequest jsonPost(URI uri, Map<String, Object> payload) throws Exception {
        return HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(payload)))
                .build();
    }

    private static String cookieFrom(HttpResponse<?> response) {
        String raw = response.headers().firstValue("Set-Cookie").orElse("");
        int end = raw.indexOf(';');
        return end >= 0 ? raw.substring(0, end) : raw;
    }

    private static String tokenFromOutbox(TestRuntime runtime) {
        DevOutboxEmailSender devOutbox = runtime.devOutboxEmailSender();
        if (devOutbox == null) {
            throw new IllegalStateException("Dev outbox is not available");
        }
        List<EmailMessage> outbox = devOutbox.recent();
        String link = outbox.get(outbox.size() - 1).links().get(0);
        int marker = link.indexOf("token=");
        return link.substring(marker + "token=".length());
    }

    private record TestRuntime(
            int port,
            UserStore userStore,
            PasswordResetStore passwordResetStore,
            PreferencesStore preferencesStore,
            JsonFileSignalStore signalStore,
            AtomicInteger refreshCount,
            EventBus eventBus,
            DevOutboxEmailSender devOutboxEmailSender
    ) {
        URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }

    private static final class TestPasswordHasher implements PasswordHashing {
        @Override
        public String hash(String password) {
            return HashingUtils.sha256(password);
        }

        @Override
        public boolean verify(String encodedHash, String password) {
            return encodedHash.equals(hash(password));
        }
    }
}

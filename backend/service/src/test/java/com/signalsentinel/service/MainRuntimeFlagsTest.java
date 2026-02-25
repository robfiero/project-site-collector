package com.signalsentinel.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainRuntimeFlagsTest {
    @Test
    void unknownAppEnvDefaultsToDevAndWarns() {
        List<String> warnings = new ArrayList<>();
        Main.RuntimeFlags flags = Main.resolveRuntimeFlags(
                Map.of("APP_ENV", "staging", "AUTH_ENABLED", "true"),
                warnings::add
        );

        assertTrue(flags.devMode());
        assertTrue(flags.authEnabled());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Unknown APP_ENV=staging")));
    }

    @Test
    void authEnabledFalseDisablesAuthFlag() {
        Main.RuntimeFlags flags = Main.resolveRuntimeFlags(
                Map.of("APP_ENV", "dev", "AUTH_ENABLED", "false"),
                ignored -> { }
        );

        assertTrue(flags.devMode());
        assertFalse(flags.authEnabled());
    }

    @Test
    void allowInsecureIsIgnoredInProd() {
        List<String> warnings = new ArrayList<>();
        Main.RuntimeFlags flags = Main.resolveRuntimeFlags(
                Map.of("APP_ENV", "prod", "AUTH_ENABLED", "true", "ALLOW_INSECURE_AUTH_HASHER", "true"),
                warnings::add
        );

        assertFalse(flags.devMode());
        assertTrue(flags.authEnabled());
        assertFalse(flags.allowInsecureAuthHasher());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("ignored in prod mode")));
    }

    @Test
    void unknownAuthEnabledDefaultsToTrueAndWarns() {
        List<String> warnings = new ArrayList<>();
        Main.RuntimeFlags flags = Main.resolveRuntimeFlags(
                Map.of("APP_ENV", "dev", "AUTH_ENABLED", "maybe"),
                warnings::add
        );

        assertTrue(flags.authEnabled());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Unknown AUTH_ENABLED=maybe")));
    }

    @Test
    void missingTicketmasterApiKeyDisablesLocalEventsCollectorAndWarns() {
        List<String> warnings = new ArrayList<>();
        var cfg = Main.buildTicketmasterConfig(
                Map.of("LOCAL_LAT", "42.3601", "LOCAL_LON", "-71.0589"),
                warnings::add
        );

        assertNull(cfg);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("TICKETMASTER_API_KEY is missing")));
    }
}

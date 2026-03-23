package com.signalsentinel.service;

public final class VersionInfo {
    private static final String DEFAULT_VERSION = "1.0.0";

    private VersionInfo() {
    }

    public static String version() {
        return readValue("APP_VERSION", DEFAULT_VERSION);
    }

    public static String buildTime() {
        return readValue("APP_BUILD_TIME", "");
    }

    public static String gitSha() {
        return readValue("APP_GIT_SHA", "");
    }

    private static String readValue(String key, String fallback) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String prop = System.getProperty(key);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        return fallback;
    }
}

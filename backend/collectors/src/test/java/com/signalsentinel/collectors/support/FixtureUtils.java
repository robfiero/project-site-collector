package com.signalsentinel.collectors.support;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

public final class FixtureUtils {
    private FixtureUtils() {
    }

    public static Path fixturePath(String relativePath) {
        URL resource = FixtureUtils.class.getClassLoader().getResource(relativePath);
        if (resource == null) {
            throw new IllegalArgumentException("Fixture not found: " + relativePath);
        }
        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid fixture URI: " + relativePath, e);
        }
    }
}

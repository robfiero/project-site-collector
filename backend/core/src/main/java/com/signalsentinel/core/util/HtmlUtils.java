package com.signalsentinel.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HtmlUtils {
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "<a\\s+[^>]*href\\s*=\\s*(['\"])(.*?)\\1",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private HtmlUtils() {
    }

    public static Optional<String> extractTitle(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String title = matcher.group(1).replaceAll("\\s+", " ").trim();
        return title.isEmpty() ? Optional.empty() : Optional.of(title);
    }

    public static List<String> extractLinks(String html) {
        Matcher matcher = LINK_PATTERN.matcher(html);
        List<String> links = new ArrayList<>();
        while (matcher.find()) {
            String link = matcher.group(2).trim();
            if (!link.isEmpty() && isAllowedLink(link)) {
                links.add(link);
            }
        }
        return links;
    }

    private static boolean isAllowedLink(String link) {
        String lowered = link.toLowerCase(Locale.ROOT);
        return !lowered.startsWith("mailto:") && !lowered.startsWith("javascript:");
    }
}

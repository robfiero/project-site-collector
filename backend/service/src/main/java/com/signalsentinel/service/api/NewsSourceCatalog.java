package com.signalsentinel.service.api;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NewsSourceCatalog {
    private static final List<NewsSource> SOURCES = List.of(
            new NewsSource("nyt", "New York Times", "json", "https://api.nytimes.com/svc/topstories/v2/home.json", false, true, "Requires NYT_API_KEY"),
            new NewsSource("nyt_most_popular", "NYT Most Popular", "json", "https://api.nytimes.com/svc/mostpopular/v2/viewed/1.json", false, true, "Requires NYT_API_KEY"),
            new NewsSource("npr_news_now", "NPR News Now (Breaking)", "rss", "https://feeds.npr.org/500005/podcast.xml", false, false, null),
            new NewsSource("npr_politics", "NPR Politics", "rss", "https://feeds.npr.org/1014/rss.xml", false, false, null),
            new NewsSource("npr_morning_edition", "NPR Morning Edition", "rss", "https://www.kuow.org/feeds/podcasts/morning-edition/podcasts/rss.xml", false, false, null),
            new NewsSource("nbc", "NBC News", "rss", "https://feeds.nbcnews.com/nbcnews/public/news", false, false, null),
            new NewsSource("cbs", "CBS News", "rss", "https://www.cbsnews.com/latest/rss/main", false, false, null),
            new NewsSource("ap", "AP News", "rss", "https://feeds.apnews.com/apf-topnews", true, false, null),
            new NewsSource("google_news", "Google News (US)", "rss", "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en", true, false, null),
            new NewsSource("fox", "Fox News", "rss", "https://moxie.foxnews.com/google-publisher/latest.xml", false, false, null),
            new NewsSource("wsj", "Wall Street Journal", "rss", "https://feeds.a.dj.com/rss/RSSWorldNews.xml", true, false, null),
            new NewsSource("abc", "ABC News", "rss", "https://feeds.abcnews.com/abcnews/topstories", false, false, null),
            new NewsSource("verge", "The Verge", "rss", "https://www.theverge.com/rss/index.xml", true, false, null)
    );

    private static final List<String> DEFAULT_SELECTED = List.of("ap", "google_news", "wsj", "verge");

    private NewsSourceCatalog() {
    }

    public static List<NewsSource> sources() {
        return SOURCES;
    }

    public static List<String> defaultSelectedSourceIds() {
        return DEFAULT_SELECTED;
    }

    public static List<Map<String, Object>> asApiList() {
        return SOURCES.stream().map(source -> {
            boolean configured = isConfigured(source.id());
            boolean requiresConfig = source.requiresConfig() && !configured;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", source.id());
            item.put("name", source.name());
            item.put("type", source.type());
            item.put("url", source.url());
            item.put("enabledByDefault", source.enabledByDefault());
            item.put("requiresConfig", requiresConfig);
            item.put("note", requiresConfig && source.note() != null ? source.note() : "");
            return item;
        }).toList();
    }

    private static boolean isConfigured(String sourceId) {
        if (sourceId.startsWith("nyt")) {
            return !System.getenv().getOrDefault("NYT_API_KEY", "").isBlank();
        }
        return true;
    }

    public record NewsSource(
            String id,
            String name,
            String type,
            String url,
            boolean enabledByDefault,
            boolean requiresConfig,
            String note
    ) {
    }
}

package com.signalsentinel.core.model;

import java.util.List;

public record SiteConfig(
        String id,
        String url,
        List<String> tags,
        ParseMode parseMode
) {
}

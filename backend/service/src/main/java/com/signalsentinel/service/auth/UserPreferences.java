package com.signalsentinel.service.auth;

import java.util.List;

public record UserPreferences(
        String userId,
        List<String> zipCodes,
        List<String> watchlist,
        List<String> newsSourceIds,
        String themeMode,
        String accent
) {
    public static final String DEFAULT_THEME_MODE = "dark";
    public static final String DEFAULT_ACCENT = "default";

    public static UserPreferences empty(String userId) {
        return new UserPreferences(userId, List.of(), List.of(), List.of(), DEFAULT_THEME_MODE, DEFAULT_ACCENT);
    }
}

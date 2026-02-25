package com.signalsentinel.service.auth;

import java.util.List;

public record UserPreferences(
        String userId,
        List<String> zipCodes,
        List<String> watchlist,
        List<String> newsSourceIds
) {
    public static UserPreferences empty(String userId) {
        return new UserPreferences(userId, List.of(), List.of(), List.of());
    }
}

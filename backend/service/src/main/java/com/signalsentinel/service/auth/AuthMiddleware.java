package com.signalsentinel.service.auth;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

public final class AuthMiddleware {
    public static final String AUTH_COOKIE_NAME = "signal_sentinel_auth";

    private AuthMiddleware() {
    }

    public static Optional<String> readAuthCookie(HttpExchange exchange) {
        String raw = exchange.getRequestHeaders().getFirst("Cookie");
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(raw.split(";"))
                .map(String::trim)
                .filter(cookie -> cookie.startsWith(AUTH_COOKIE_NAME + "="))
                .findFirst()
                .map(cookie -> cookie.substring((AUTH_COOKIE_NAME + "=").length()));
    }

    public static Optional<AuthUser> requireUser(HttpExchange exchange, AuthService authService) throws IOException {
        Optional<String> token = readAuthCookie(exchange);
        if (token.isEmpty()) {
            writeUnauthorized(exchange);
            return Optional.empty();
        }
        Optional<AuthUser> user = authService.userForToken(token.get());
        if (user.isEmpty()) {
            writeUnauthorized(exchange);
            return Optional.empty();
        }
        return user;
    }

    public static String buildAuthCookie(String jwt, boolean secure, String sameSite) {
        StringBuilder cookie = new StringBuilder();
        cookie.append(AUTH_COOKIE_NAME).append("=").append(jwt)
                .append("; Path=/; HttpOnly; SameSite=").append(sameSite).append("; Max-Age=").append(60 * 60 * 8);
        if (secure) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }

    public static String buildClearCookie(boolean secure, String sameSite) {
        StringBuilder cookie = new StringBuilder();
        cookie.append(AUTH_COOKIE_NAME).append("=; Path=/; HttpOnly; SameSite=").append(sameSite).append("; Max-Age=0");
        if (secure) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }

    private static void writeUnauthorized(HttpExchange exchange) throws IOException {
        byte[] payload = com.signalsentinel.core.util.JsonUtils.objectMapper().writeValueAsBytes(Map.of("error", "unauthorized"));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(401, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}

package com.signalsentinel.service.auth;

import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.LoginFailed;
import com.signalsentinel.core.events.LoginSucceeded;
import com.signalsentinel.core.events.PasswordResetFailed;
import com.signalsentinel.core.events.PasswordResetRequested;
import com.signalsentinel.core.events.PasswordResetSucceeded;
import com.signalsentinel.core.events.UserRegistered;
import com.signalsentinel.service.email.EmailMessage;
import com.signalsentinel.service.email.EmailSender;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class AuthService {
    private static final Set<String> ALLOWED_THEME_MODES = Set.of("light", "dark");
    private static final Set<String> ALLOWED_ACCENTS = Set.of("default", "gold", "blue", "green");
    private final UserStore userStore;
    private final PreferencesStore preferencesStore;
    private final PasswordResetStore passwordResetStore;
    private final PasswordHashing passwordHasher;
    private final JwtService jwtService;
    private final ResetTokenService resetTokenService;
    private final EmailSender emailSender;
    private final EventBus eventBus;
    private final Clock clock;
    private final String appBaseUrl;
    private final SimpleRateLimiter forgotByIpLimiter;
    private final SimpleRateLimiter forgotByEmailLimiter;
    private final Duration resetTokenTtl;

    public AuthService(
            UserStore userStore,
            PreferencesStore preferencesStore,
            PasswordResetStore passwordResetStore,
            PasswordHashing passwordHasher,
            JwtService jwtService,
            ResetTokenService resetTokenService,
            EmailSender emailSender,
            EventBus eventBus,
            Clock clock,
            String appBaseUrl
    ) {
        this.userStore = userStore;
        this.preferencesStore = preferencesStore;
        this.passwordResetStore = passwordResetStore;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        this.resetTokenService = resetTokenService;
        this.emailSender = emailSender;
        this.eventBus = eventBus;
        this.clock = clock;
        this.appBaseUrl = appBaseUrl;
        this.forgotByIpLimiter = new SimpleRateLimiter(clock, 10, 60);
        this.forgotByEmailLimiter = new SimpleRateLimiter(clock, 5, 60);
        this.resetTokenTtl = Duration.ofMinutes(15);
    }

    public AuthResult signup(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        if (userStore.findByEmail(normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }
        Instant now = clock.instant();
        AuthUser user = new AuthUser(
                UUID.randomUUID().toString(),
                normalizedEmail,
                passwordHasher.hash(password),
                now,
                now
        );
        userStore.save(user);
        preferencesStore.putForUser(UserPreferences.empty(user.id()));
        eventBus.publish(new UserRegistered(now, user.id(), user.email()));
        return new AuthResult(user.id(), user.email(), jwtService.issue(user.id(), user.email()));
    }

    public AuthResult login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        Optional<AuthUser> maybeUser = userStore.findByEmail(normalizedEmail);
        if (maybeUser.isEmpty()) {
            eventBus.publish(new LoginFailed(clock.instant(), normalizedEmail, "user_not_found"));
            throw new IllegalArgumentException("Invalid credentials");
        }
        AuthUser user = maybeUser.get();
        if (!passwordHasher.verify(user.passwordHash(), password)) {
            eventBus.publish(new LoginFailed(clock.instant(), normalizedEmail, "password_mismatch"));
            throw new IllegalArgumentException("Invalid credentials");
        }
        Instant now = clock.instant();
        AuthUser updated = user.withLastLoginAt(now);
        userStore.save(updated);
        eventBus.publish(new LoginSucceeded(now, updated.id(), updated.email()));
        return new AuthResult(updated.id(), updated.email(), jwtService.issue(updated.id(), updated.email()));
    }

    public Optional<AuthUser> userForToken(String token) {
        return jwtService.verify(token)
                .flatMap(claims -> userStore.findById(claims.userId()));
    }

    public UserPreferences getPreferences(String userId) {
        UserPreferences current = preferencesStore.getForUser(userId);
        return new UserPreferences(
                userId,
                current.zipCodes(),
                current.watchlist(),
                current.newsSourceIds(),
                normalizeChoice(current.themeMode(), ALLOWED_THEME_MODES, UserPreferences.DEFAULT_THEME_MODE),
                normalizeChoice(current.accent(), ALLOWED_ACCENTS, UserPreferences.DEFAULT_ACCENT)
        );
    }

    public UserPreferences updatePreferences(String userId, UserPreferences incoming) {
        if (incoming.zipCodes().size() > 10) {
            throw new IllegalArgumentException("ZIP code limit exceeded");
        }
        if (incoming.watchlist().size() > 20) {
            throw new IllegalArgumentException("Watchlist limit exceeded");
        }
        UserPreferences validated = new UserPreferences(
                userId,
                incoming.zipCodes().stream().map(String::trim).filter(v -> !v.isBlank()).toList(),
                incoming.watchlist().stream().map(String::trim).map(String::toUpperCase).filter(v -> !v.isBlank()).toList(),
                incoming.newsSourceIds().stream().map(String::trim).filter(v -> !v.isBlank()).toList(),
                normalizeChoice(incoming.themeMode(), ALLOWED_THEME_MODES, UserPreferences.DEFAULT_THEME_MODE),
                normalizeChoice(incoming.accent(), ALLOWED_ACCENTS, UserPreferences.DEFAULT_ACCENT)
        );
        return preferencesStore.putForUser(validated);
    }

    private static String normalizeChoice(String rawValue, Set<String> allowed, String fallback) {
        if (rawValue == null) {
            return fallback;
        }
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if (allowed.contains(normalized)) {
            return normalized;
        }
        return fallback;
    }

    public void requestPasswordReset(String email, String ipAddress) {
        String normalizedEmail = normalizeEmail(email);
        if (!forgotByIpLimiter.tryAcquire("ip:" + ipAddress) || !forgotByEmailLimiter.tryAcquire("email:" + normalizedEmail)) {
            return;
        }

        Optional<AuthUser> maybeUser = userStore.findByEmail(normalizedEmail);
        if (maybeUser.isEmpty()) {
            return;
        }

        AuthUser user = maybeUser.get();
        String rawToken = resetTokenService.generateRawToken();
        String tokenHash = resetTokenService.hashToken(rawToken);
        passwordResetStore.save(new PasswordResetTokenRecord(
                tokenHash,
                user.id(),
                clock.instant().plus(resetTokenTtl),
                false
        ));
        String link = appBaseUrl + "/#/reset?token=" + rawToken;
        emailSender.send(new EmailMessage(
                user.email(),
                "Today's Overview password reset",
                "Use this link to reset your password: " + link,
                List.of(link),
                clock.instant()
        ));
        eventBus.publish(new PasswordResetRequested(clock.instant(), user.email()));
    }

    public void resetPassword(String token, String newPassword) {
        validatePassword(newPassword);
        String tokenHash = resetTokenService.hashToken(token);
        Optional<PasswordResetTokenRecord> record = passwordResetStore.findByHash(tokenHash);
        if (record.isEmpty()) {
            eventBus.publish(new PasswordResetFailed(clock.instant(), "unknown", "token_not_found"));
            throw new IllegalArgumentException("Invalid reset token");
        }
        PasswordResetTokenRecord value = record.get();
        if (value.used() || value.isExpired(clock.instant())) {
            eventBus.publish(new PasswordResetFailed(clock.instant(), "unknown", value.used() ? "token_used" : "token_expired"));
            throw new IllegalArgumentException("Invalid reset token");
        }
        AuthUser user = userStore.findById(value.userId())
                .orElseThrow(() -> new IllegalArgumentException("Invalid reset token"));
        userStore.save(user.withPasswordHash(passwordHasher.hash(newPassword)));
        passwordResetStore.replace(value.markUsed());
        eventBus.publish(new PasswordResetSucceeded(clock.instant(), user.id(), user.email()));
    }

    private static String normalizeEmail(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Email is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || !normalized.contains("@")) {
            throw new IllegalArgumentException("Invalid email");
        }
        return normalized;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
    }

    public record AuthResult(String userId, String email, String jwt) {
    }
}

package com.arenasystem.bot;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public record AppConfig(
        int port,
        String telegramBotToken,
        String telegramWebhookSecret,
        String publicBaseUrl,
        String githubRepository,
        Set<String> allowedChatIds
) {
    public static AppConfig fromEnvironment() {
        int port = parsePort(System.getenv().getOrDefault("PORT", "8080"));
        String token = value("TELEGRAM_BOT_TOKEN", "");
        String secret = value("TELEGRAM_WEBHOOK_SECRET", "");
        String publicUrl = value("PUBLIC_BASE_URL", "").replaceAll("/+$", "");
        String repository = value(
                "GITHUB_REPOSITORY",
                "au3891427-wq/Arena-System-Plugins-Client-and-Server"
        );
        Set<String> allowedIds = Arrays.stream(
                        value("TELEGRAM_ALLOWED_CHAT_IDS", "").split(",")
                )
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        AppConfig config = new AppConfig(
                port, token, secret, publicUrl, repository, allowedIds
        );
        config.validate();
        return config;
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value.trim();
    }

    private static int parsePort(String raw) {
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 1 || parsed > 65535) {
                throw new IllegalArgumentException("PORT must be between 1 and 65535.");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("PORT must be a valid TCP port.", exception);
        }
    }

    private void validate() {
        if (telegramBotToken.isBlank()) {
            throw new IllegalArgumentException("TELEGRAM_BOT_TOKEN is required.");
        }
        if (!telegramWebhookSecret.matches("[A-Za-z0-9_-]{1,256}")) {
            throw new IllegalArgumentException(
                    "TELEGRAM_WEBHOOK_SECRET must contain 1-256 letters, numbers, underscores, or hyphens."
            );
        }
        if (!publicBaseUrl.isBlank()) {
            URI uri;
            try {
                uri = URI.create(publicBaseUrl);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("PUBLIC_BASE_URL must be a valid HTTPS URL.", exception);
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("PUBLIC_BASE_URL must use HTTPS and include a hostname.");
            }
        }
        if (!githubRepository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("GITHUB_REPOSITORY must use owner/repository format.");
        }
    }
}

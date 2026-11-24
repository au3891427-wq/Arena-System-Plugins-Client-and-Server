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
        String announcementApiKey,
        Set<String> allowedChatIds,
        String releaseMonitorStateFile,
        int releaseMonitorIntervalSeconds,
        String nineRouterBaseUrl,
        String nineRouterApiKey,
        String nineRouterModel,
        int nineRouterTimeoutSeconds
) {
    public static AppConfig fromEnvironment() {
        int port = parsePort(value("PORT", "8080"));
        String token = value("TELEGRAM_BOT_TOKEN", "");
        String secret = value("TELEGRAM_WEBHOOK_SECRET", "");
        String publicUrl = value("PUBLIC_BASE_URL", "").replaceAll("/+$", "");
        String repository = value(
                "GITHUB_REPOSITORY",
                "au3891427-wq/Arena-System-Plugins-Client-and-Server"
        );
        String announcementApiKey = value("ANNOUNCEMENT_API_KEY", "");
        String releaseStateFile = value(
                "RELEASE_MONITOR_STATE_FILE",
                "data/release-monitor-state.json"
        );
        int releaseMonitorIntervalSeconds = parseReleaseMonitorInterval(
                value("RELEASE_MONITOR_INTERVAL_SECONDS", "60")
        );
        String nineRouterBaseUrl = value(
                "NINEROUTER_BASE_URL",
                "https://9router.com/v1"
        ).replaceAll("/+$", "");
        String nineRouterApiKey = value("NINEROUTER_API_KEY", "");
        String nineRouterModel = value("NINEROUTER_MODEL", "");
        int nineRouterTimeoutSeconds = parseNineRouterTimeout(
                value("NINEROUTER_TIMEOUT_SECONDS", "45")
        );
        Set<String> allowedIds = Arrays.stream(
                        value("TELEGRAM_ALLOWED_CHAT_IDS", "").split(",")
                )
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        AppConfig config = new AppConfig(
                port,
                token,
                secret,
                publicUrl,
                repository,
                announcementApiKey,
                allowedIds,
                releaseStateFile,
                releaseMonitorIntervalSeconds,
                nineRouterBaseUrl,
                nineRouterApiKey,
                nineRouterModel,
                nineRouterTimeoutSeconds
        );
        config.validate();
        return config;
    }

    public boolean nineRouterEnabled() {
        return !nineRouterApiKey.isBlank() && !nineRouterModel.isBlank();
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value.trim();
    }

    private static int parseReleaseMonitorInterval(String raw) {
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 30 || parsed > 3600) {
                throw new IllegalArgumentException(
                        "RELEASE_MONITOR_INTERVAL_SECONDS must be between 30 and 3600."
                );
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "RELEASE_MONITOR_INTERVAL_SECONDS must be a valid integer.",
                    exception
            );
        }
    }

    private static int parseNineRouterTimeout(String raw) {
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 5 || parsed > 120) {
                throw new IllegalArgumentException(
                        "NINEROUTER_TIMEOUT_SECONDS must be between 5 and 120."
                );
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "NINEROUTER_TIMEOUT_SECONDS must be a valid integer.",
                    exception
            );
        }
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
        if (!announcementApiKey.isBlank() && announcementApiKey.length() < 32) {
            throw new IllegalArgumentException("ANNOUNCEMENT_API_KEY must contain at least 32 characters when enabled.");
        }
        if (releaseMonitorStateFile.isBlank()) {
            throw new IllegalArgumentException("RELEASE_MONITOR_STATE_FILE must not be blank.");
        }

        if (nineRouterApiKey.isBlank() != nineRouterModel.isBlank()) {
            throw new IllegalArgumentException(
                    "NINEROUTER_API_KEY and NINEROUTER_MODEL must either both be set or both be empty."
            );
        }
        validateNineRouterBaseUrl();
    }

    private void validateNineRouterBaseUrl() {
        URI uri;
        try {
            uri = URI.create(nineRouterBaseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("NINEROUTER_BASE_URL must be a valid URL.", exception);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        boolean secure = "https".equals(scheme);
        boolean local = "http".equals(scheme)
                && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1"));
        boolean railwayPrivate = "http".equals(scheme) && host.endsWith(".railway.internal");

        if ((!secure && !local && !railwayPrivate) || host.isBlank()) {
            throw new IllegalArgumentException(
                    "NINEROUTER_BASE_URL must use HTTPS (HTTP is allowed only for localhost or Railway private networking)."
            );
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "NINEROUTER_BASE_URL must not contain a query string or fragment."
            );
        }
    }
}

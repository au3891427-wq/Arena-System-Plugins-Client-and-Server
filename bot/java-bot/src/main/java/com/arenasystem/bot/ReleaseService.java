package com.arenasystem.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ReleaseService {
    public record Asset(String name, String downloadUrl) {}

    public record Release(
            String name,
            String tag,
            String publishedAt,
            String notes,
            String htmlUrl,
            List<Asset> assets
    ) {}

    public record ReleaseAsset(
            long id,
            String name,
            String state,
            String downloadUrl,
            Instant createdAt
    ) {}

    public record PublishedRelease(
            long id,
            String name,
            String tag,
            Instant publishedAt,
            String notes,
            String htmlUrl,
            List<ReleaseAsset> assets
    ) {}

    private static final long CACHE_TTL_MILLIS = 60_000L;
    private final String repository;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private volatile CacheEntry cache;
    private volatile String releasesEtag;
    private volatile List<PublishedRelease> publishedReleasesCache;

    private record CacheEntry(long expiresAt, Optional<Release> release) {}

    public ReleaseService(String repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    public Optional<Release> latestStable() throws IOException, InterruptedException {
        CacheEntry current = cache;
        if (current != null && current.expiresAt() > System.currentTimeMillis()) {
            return current.release();
        }

        String url = "https://api.github.com/repos/" + repository + "/releases?per_page=20";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ArenaSystem-Telegram-Assistant")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub Releases API returned HTTP " + response.statusCode() + ".");
        }

        JsonNode releases = mapper.readTree(response.body());
        if (!releases.isArray()) {
            throw new IOException("GitHub Releases API returned an unexpected payload.");
        }

        Optional<Release> found = Optional.empty();
        for (JsonNode item : releases) {
            if (item.path("draft").asBoolean(true) || item.path("prerelease").asBoolean(true)) {
                continue;
            }
            List<Asset> assets = readJarAssets(item.path("assets"));
            if (assets.isEmpty()) {
                continue;
            }
            found = Optional.of(new Release(
                    item.path("name").asText(item.path("tag_name").asText("ArenaSystem")),
                    item.path("tag_name").asText("unknown"),
                    item.path("published_at").asText(""),
                    item.path("body").asText(""),
                    item.path("html_url").asText("https://github.com/" + repository + "/releases"),
                    List.copyOf(assets)
            ));
            break;
        }
        cache = new CacheEntry(System.currentTimeMillis() + CACHE_TTL_MILLIS, found);
        return found;
    }

    /**
     * Returns stable published releases and their asset IDs/timestamps.
     * Uses ETag revalidation so unchanged polling responses can return HTTP 304.
     */
    public List<PublishedRelease> publishedStableReleases()
            throws IOException, InterruptedException {
        String url = "https://api.github.com/repos/" + repository + "/releases?per_page=100";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ArenaSystem-Release-Monitor")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET();

        String etag = releasesEtag;
        if (etag != null && publishedReleasesCache != null) {
            requestBuilder.header("If-None-Match", etag);
        }

        HttpResponse<String> response = client.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() == 304) {
            return publishedReleasesCache;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "GitHub Releases API returned HTTP " + response.statusCode() + "."
            );
        }

        JsonNode releases = mapper.readTree(response.body());
        if (!releases.isArray()) {
            throw new IOException("GitHub Releases API returned an unexpected payload.");
        }

        List<PublishedRelease> result = new ArrayList<>();
        for (JsonNode item : releases) {
            if (item.path("draft").asBoolean(true) || item.path("prerelease").asBoolean(true)) {
                continue;
            }

            long releaseId = item.path("id").asLong(0);
            Instant publishedAt = parseInstant(item.path("published_at").asText(""));
            if (releaseId <= 0 || publishedAt == null) {
                continue;
            }

            List<ReleaseAsset> assets = new ArrayList<>();
            JsonNode assetsNode = item.path("assets");
            if (assetsNode.isArray()) {
                for (JsonNode asset : assetsNode) {
                    long assetId = asset.path("id").asLong(0);
                    String name = asset.path("name").asText("");
                    String state = asset.path("state").asText("");
                    String downloadUrl = asset.path("browser_download_url").asText("");
                    Instant createdAt = parseInstant(asset.path("created_at").asText(""));

                    if (assetId > 0 && !name.isBlank()) {
                        assets.add(new ReleaseAsset(
                                assetId, name, state, downloadUrl, createdAt
                        ));
                    }
                }
            }

            result.add(new PublishedRelease(
                    releaseId,
                    item.path("name").asText(item.path("tag_name").asText("ArenaSystem")),
                    item.path("tag_name").asText("unknown"),
                    publishedAt,
                    item.path("body").asText(""),
                    item.path("html_url").asText(
                            "https://github.com/" + repository + "/releases"
                    ),
                    List.copyOf(assets)
            ));
        }

        releasesEtag = response.headers().firstValue("ETag").orElse(null);
        publishedReleasesCache = List.copyOf(result);
        return publishedReleasesCache;
    }

    private List<Asset> readJarAssets(JsonNode assetsNode) {
        List<Asset> assets = new ArrayList<>();
        if (!assetsNode.isArray()) {
            return assets;
        }
        for (JsonNode asset : assetsNode) {
            String name = asset.path("name").asText("");
            String state = asset.path("state").asText("uploaded");
            String url = asset.path("browser_download_url").asText("");
            if ("uploaded".equals(state)
                    && name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                    && !url.isBlank()) {
                assets.add(new Asset(name, url));
            }
        }
        return assets;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    public String releasesUrl() {
        return "https://github.com/" + repository + "/releases";
    }
}

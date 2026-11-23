package com.arenasystem.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ReleaseMonitor {
    private static final System.Logger LOGGER = System.getLogger(ReleaseMonitor.class.getName());
    private static final int MAX_TRACKED_ASSETS = 5000;

    private final ReleaseService releases;
    private final TelegramApi telegram;
    private final ObjectMapper mapper;
    private final Path stateFile;
    private final int intervalSeconds;
    private final Object stateLock = new Object();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "arenasystem-release-monitor");
                thread.setDaemon(true);
                return thread;
            });

    private MonitorState state;

    private static final class MonitorState {
        private Long chatId;
        private Long threadId;
        private final LinkedHashSet<Long> processedAssetIds = new LinkedHashSet<>();
    }

    public ReleaseMonitor(
            ReleaseService releases,
            TelegramApi telegram,
            ObjectMapper mapper,
            Path stateFile,
            int intervalSeconds
    ) throws IOException {
        this.releases = releases;
        this.telegram = telegram;
        this.mapper = mapper;
        this.stateFile = stateFile.toAbsolutePath().normalize();
        this.intervalSeconds = intervalSeconds;
        this.state = loadState();
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::pollSafely,
                10,
                intervalSeconds,
                TimeUnit.SECONDS
        );
        LOGGER.log(System.Logger.Level.INFO,
                "Release asset monitor started; interval=" + intervalSeconds + " seconds.");
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public void setAnnouncementTarget(long chatId, long threadId) throws IOException {
        if (threadId <= 0) {
            throw new IllegalArgumentException("Telegram topic ID must be a positive integer.");
        }

        synchronized (stateLock) {
            state.chatId = chatId;
            state.threadId = threadId;
            persistState();
        }
    }

    static boolean isPostPublicationJar(
            ReleaseService.PublishedRelease release,
            ReleaseService.ReleaseAsset asset
    ) {
        return asset.state().equals("uploaded")
                && asset.name().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                && !asset.downloadUrl().isBlank()
                && asset.createdAt() != null
                && asset.createdAt().isAfter(release.publishedAt());
    }

    private void pollSafely() {
        try {
            poll();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Release asset scan failed: " + exception.getMessage());
        }
    }

    private void poll() throws IOException, InterruptedException {
        Long chatId;
        Long threadId;
        Set<Long> processedSnapshot;

        synchronized (stateLock) {
            chatId = state.chatId;
            threadId = state.threadId;
            processedSnapshot = Set.copyOf(state.processedAssetIds);
        }

        if (chatId == null || threadId == null) {
            return;
        }

        List<ReleaseService.PublishedRelease> stableReleases =
                releases.publishedStableReleases();

        for (ReleaseService.PublishedRelease release : stableReleases) {
            List<ReleaseService.ReleaseAsset> newAssets = new ArrayList<>();
            for (ReleaseService.ReleaseAsset asset : release.assets()) {
                if (isPostPublicationJar(release, asset)
                        && !processedSnapshot.contains(asset.id())) {
                    newAssets.add(asset);
                }
            }

            if (newAssets.isEmpty()) {
                continue;
            }

            telegram.sendMessage(
                    chatId,
                    buildMessage(release, newAssets),
                    threadId,
                    buildKeyboard(release, newAssets)
            );

            synchronized (stateLock) {
                for (ReleaseService.ReleaseAsset asset : newAssets) {
                    state.processedAssetIds.add(asset.id());
                }
                trimProcessedAssets();
                persistState();
                processedSnapshot = Set.copyOf(state.processedAssetIds);
            }

            LOGGER.log(System.Logger.Level.INFO,
                    "Announced " + newAssets.size() + " new JAR asset(s) for release " +
                            release.tag() + ".");
        }
    }

    private String buildMessage(
            ReleaseService.PublishedRelease release,
            List<ReleaseService.ReleaseAsset> assets
    ) {
        StringBuilder message = new StringBuilder()
                .append("📦 ARENASYSTEM — FILE JAR BARU TERSEDIA\n\n")
                .append(release.name()).append("\n")
                .append("Versi: ").append(release.tag()).append("\n")
                .append("Diterbitkan: ").append(release.publishedAt()).append("\n\n")
                .append("File yang baru diunggah:\n");

        for (ReleaseService.ReleaseAsset asset : assets) {
            message.append("• ").append(asset.name()).append("\n");
        }

        message.append("\nUnduhan tersedia melalui tombol di bawah.");
        return message.toString();
    }

    private ObjectNode buildKeyboard(
            ReleaseService.PublishedRelease release,
            List<ReleaseService.ReleaseAsset> assets
    ) {
        ArrayNode rows = mapper.createArrayNode();
        for (ReleaseService.ReleaseAsset asset : assets.stream().limit(8).toList()) {
            ArrayNode row = mapper.createArrayNode();
            row.add(mapper.createObjectNode()
                    .put("text", "⬇️ " + clip(asset.name(), 48))
                    .put("url", asset.downloadUrl()));
            rows.add(row);
        }

        ArrayNode finalRow = mapper.createArrayNode();
        finalRow.add(mapper.createObjectNode()
                .put("text", "📝 Catatan Perubahan")
                .put("url", release.htmlUrl()));
        finalRow.add(mapper.createObjectNode()
                .put("text", "📚 Semua Rilis")
                .put("url", releases.releasesUrl()));
        rows.add(finalRow);

        ObjectNode keyboard = mapper.createObjectNode();
        keyboard.set("inline_keyboard", rows);
        return keyboard;
    }

    private MonitorState loadState() throws IOException {
        MonitorState loaded = new MonitorState();
        if (!Files.exists(stateFile)) {
            return loaded;
        }

        JsonNode root = mapper.readTree(Files.readString(stateFile, StandardCharsets.UTF_8));
        if (root == null || !root.isObject()) {
            throw new IOException("Release monitor state file must contain a JSON object.");
        }

        if (root.path("announcement_chat_id").canConvertToLong()
                && root.path("announcement_thread_id").canConvertToLong()) {
            loaded.chatId = root.path("announcement_chat_id").asLong();
            loaded.threadId = root.path("announcement_thread_id").asLong();
        }

        JsonNode ids = root.path("processed_asset_ids");
        if (ids.isArray()) {
            for (JsonNode id : ids) {
                if (id.canConvertToLong() && id.asLong() > 0) {
                    loaded.processedAssetIds.add(id.asLong());
                }
            }
        }
        return loaded;
    }

    private void persistState() throws IOException {
        Path parent = stateFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ObjectNode root = mapper.createObjectNode()
                .put("version", 1);
        if (state.chatId != null && state.threadId != null) {
            root.put("announcement_chat_id", state.chatId);
            root.put("announcement_thread_id", state.threadId);
        }

        ArrayNode ids = root.putArray("processed_asset_ids");
        for (Long id : state.processedAssetIds) {
            ids.add(id);
        }

        Path tempFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        Files.writeString(
                tempFile,
                mapper.writeValueAsString(root) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        try {
            Files.move(
                    tempFile,
                    stateFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    tempFile,
                    stateFile,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private void trimProcessedAssets() {
        while (state.processedAssetIds.size() > MAX_TRACKED_ASSETS) {
            Long oldest = state.processedAssetIds.iterator().next();
            state.processedAssetIds.remove(oldest);
        }
    }

    private static String clip(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(1, maxLength - 1)).stripTrailing() + "…";
    }
}

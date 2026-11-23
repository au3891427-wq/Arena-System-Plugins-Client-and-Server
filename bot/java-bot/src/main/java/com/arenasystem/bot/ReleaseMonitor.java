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
import java.time.Duration;
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
    static final Duration BATCH_QUIET_PERIOD = Duration.ofSeconds(5);
    static final Duration MAX_BATCH_WINDOW = Duration.ofSeconds(20);

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

    record PendingAsset(
            ReleaseService.PublishedRelease release,
            ReleaseService.ReleaseAsset asset
    ) {}

    private enum ComponentType {
        MOD("🧩 MOD — CLIENT / FABRIC", "MOD"),
        PLUGIN("🔌 PLUGIN — SERVER / BUKKIT", "PLUGIN"),
        OTHER("📦 KOMPONEN LAIN", "FILE");

        private final String sectionTitle;
        private final String buttonLabel;

        ComponentType(String sectionTitle, String buttonLabel) {
            this.sectionTitle = sectionTitle;
            this.buttonLabel = buttonLabel;
        }
    }

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
        Set<Long> discoveredAssetIds = new LinkedHashSet<>();
        List<PendingAsset> pendingAssets = new ArrayList<>(
                collectNewAssets(stableReleases, processedSnapshot, discoveredAssetIds)
        );

        if (pendingAssets.isEmpty()) {
            return;
        }

        // Debounce sequential uploads so client and server JARs are announced together.
        collectDuringQuietPeriod(pendingAssets, processedSnapshot, discoveredAssetIds);

        telegram.sendMessage(
                chatId,
                buildMessage(pendingAssets),
                threadId,
                buildKeyboard(pendingAssets)
        );

        synchronized (stateLock) {
            for (PendingAsset pending : pendingAssets) {
                state.processedAssetIds.add(pending.asset().id());
            }
            trimProcessedAssets();
            persistState();
        }

        long releaseCount = pendingAssets.stream()
                .map(pending -> pending.release().id())
                .distinct()
                .count();
        LOGGER.log(System.Logger.Level.INFO,
                "Announced " + pendingAssets.size() + " new JAR asset(s) across " +
                        releaseCount + " release(s) in one Telegram message.");
    }

    static List<PendingAsset> collectNewAssets(
            List<ReleaseService.PublishedRelease> releases,
            Set<Long> processedAssetIds,
            Set<Long> discoveredAssetIds
    ) {
        List<PendingAsset> found = new ArrayList<>();
        for (ReleaseService.PublishedRelease release : releases) {
            for (ReleaseService.ReleaseAsset asset : release.assets()) {
                if (isPostPublicationJar(release, asset)
                        && !processedAssetIds.contains(asset.id())
                        && discoveredAssetIds.add(asset.id())) {
                    found.add(new PendingAsset(release, asset));
                }
            }
        }
        return found;
    }

    private void collectDuringQuietPeriod(
            List<PendingAsset> pendingAssets,
            Set<Long> processedAssetIds,
            Set<Long> discoveredAssetIds
    ) throws IOException, InterruptedException {
        long batchStartedAt = System.nanoTime();
        long lastNewAssetAt = batchStartedAt;
        long quietPeriodNanos = BATCH_QUIET_PERIOD.toNanos();
        long maxWindowNanos = MAX_BATCH_WINDOW.toNanos();

        while (true) {
            long now = System.nanoTime();
            long quietDeadline = lastNewAssetAt + quietPeriodNanos;
            long maxDeadline = batchStartedAt + maxWindowNanos;
            long sleepNanos = Math.min(quietDeadline, maxDeadline) - now;

            if (sleepNanos <= 0) {
                break;
            }

            TimeUnit.NANOSECONDS.sleep(sleepNanos);

            // Bypass ETag for this short recheck so a newly uploaded asset is not hidden
            // behind a cached 304 response during the debounce window.
            List<ReleaseService.PublishedRelease> refreshedReleases =
                    releases.publishedStableReleases(true);
            int previousCount = pendingAssets.size();
            pendingAssets.addAll(collectNewAssets(
                    refreshedReleases,
                    processedAssetIds,
                    discoveredAssetIds
            ));

            if (pendingAssets.size() > previousCount) {
                lastNewAssetAt = System.nanoTime();
                LOGGER.log(System.Logger.Level.INFO,
                        "Detected additional JAR asset(s) during the announcement window; " +
                                "extending the batch quiet period.");
            }

            now = System.nanoTime();
            if (now - lastNewAssetAt >= quietPeriodNanos
                    || now - batchStartedAt >= maxWindowNanos) {
                break;
            }
        }
    }

    static String componentLabel(ReleaseService.PublishedRelease release) {
        return componentType(release).sectionTitle;
    }

    private static ComponentType componentType(
            ReleaseService.PublishedRelease release
    ) {
        String tag = release.tag().toLowerCase(java.util.Locale.ROOT);
        String name = release.name().toLowerCase(java.util.Locale.ROOT);

        if (tag.contains("fabric") || tag.contains("mod") || name.contains("client")) {
            return ComponentType.MOD;
        }
        if (tag.contains("bukkit") || tag.contains("paper")
                || tag.contains("plugin") || name.contains("server")) {
            return ComponentType.PLUGIN;
        }
        return ComponentType.OTHER;
    }

    private String buildMessage(List<PendingAsset> assets) {
        StringBuilder message = new StringBuilder()
                .append("📦 ARENASYSTEM — UPDATE BARU TERSEDIA\n")
                .append("Ada ").append(assets.size())
                .append(" file JAR baru:\n");

        for (ComponentType type : ComponentType.values()) {
            List<PendingAsset> groupedAssets = assets.stream()
                    .filter(pending -> componentType(pending.release()) == type)
                    .toList();
            if (groupedAssets.isEmpty()) {
                continue;
            }

            message.append("\n").append(type.sectionTitle).append("\n");
            for (PendingAsset pending : groupedAssets) {
                ReleaseService.PublishedRelease release = pending.release();
                message.append("• ").append(pending.asset().name()).append("\n")
                        .append("  Rilis: ").append(release.name())
                        .append(" (").append(release.tag()).append(")\n");
            }
        }

        message.append("\nUnduh file melalui tombol di bawah.");
        return message.toString();
    }

    private ObjectNode buildKeyboard(List<PendingAsset> assets) {
        ArrayNode rows = mapper.createArrayNode();

        for (PendingAsset pending : assets.stream().limit(8).toList()) {
            ComponentType type = componentType(pending.release());
            ArrayNode row = mapper.createArrayNode();
            row.add(mapper.createObjectNode()
                    .put("text", "⬇️ " + type.buttonLabel + ": "
                            + clip(pending.asset().name(), 40))
                    .put("url", pending.asset().downloadUrl()));
            rows.add(row);
        }

        LinkedHashSet<Long> seenReleaseIds = new LinkedHashSet<>();
        for (PendingAsset pending : assets) {
            ReleaseService.PublishedRelease release = pending.release();
            if (!seenReleaseIds.add(release.id())) {
                continue;
            }

            ComponentType type = componentType(release);
            ArrayNode row = mapper.createArrayNode();
            row.add(mapper.createObjectNode()
                    .put("text", "📝 Catatan " + type.buttonLabel)
                    .put("url", release.htmlUrl()));
            rows.add(row);
        }

        ArrayNode finalRow = mapper.createArrayNode();
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

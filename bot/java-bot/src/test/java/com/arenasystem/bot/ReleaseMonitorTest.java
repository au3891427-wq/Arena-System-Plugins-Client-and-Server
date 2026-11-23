package com.arenasystem.bot;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseMonitorTest {
    private static final Instant PUBLISHED_AT = Instant.parse("2026-11-23T10:00:00Z");

    @Test
    void classifiesFabricClientReleaseAsMod() {
        assertEquals(
                "🧩 MOD — CLIENT / FABRIC",
                ReleaseMonitor.componentLabel(release("ArenaSystem Client", "Fabric"))
        );
    }

    @Test
    void classifiesBukkitServerReleaseAsPlugin() {
        assertEquals(
                "🔌 PLUGIN — SERVER / BUKKIT",
                ReleaseMonitor.componentLabel(release("ArenaSystem Server", "Bukkit"))
        );
    }

    @Test
    void leavesUnknownReleaseInSeparateGroup() {
        assertEquals(
                "📦 KOMPONEN LAIN",
                ReleaseMonitor.componentLabel(release("ArenaSystem Tools", "Tools"))
        );
    }

    @Test
    void collectsModAndPluginJarsIntoOneBatch() {
        ReleaseService.PublishedRelease fabric = release(
                455025800L, "ArenaSystem Client", "Fabric"
        );
        ReleaseService.PublishedRelease bukkit = release(
                455024822L, "ArenaSystem Server", "Bukkit"
        );
        Set<Long> discoveredIds = new HashSet<>();

        List<ReleaseMonitor.PendingAsset> batch = ReleaseMonitor.collectNewAssets(
                List.of(
                        releaseWithAsset(fabric, asset(781772680L, "client.jar")),
                        releaseWithAsset(bukkit, asset(781850857L, "server.jar"))
                ),
                Set.of(),
                discoveredIds
        );

        assertEquals(2, batch.size());
        assertEquals("client.jar", batch.get(0).asset().name());
        assertEquals("server.jar", batch.get(1).asset().name());
    }

    @Test
    void doesNotAddPreviouslyProcessedOrAlreadyCollectedAssetsAgain() {
        ReleaseService.PublishedRelease bukkit = release();
        ReleaseService.PublishedRelease withAssets = releaseWithAsset(
                bukkit,
                asset(781723501L, "server.jar")
        );
        Set<Long> discoveredIds = new HashSet<>();

        List<ReleaseMonitor.PendingAsset> firstBatch = ReleaseMonitor.collectNewAssets(
                List.of(withAssets), Set.of(), discoveredIds
        );
        List<ReleaseMonitor.PendingAsset> secondScan = ReleaseMonitor.collectNewAssets(
                List.of(withAssets), Set.of(), discoveredIds
        );
        List<ReleaseMonitor.PendingAsset> processedAsset = ReleaseMonitor.collectNewAssets(
                List.of(withAssets), Set.of(781723501L), new HashSet<>()
        );

        assertEquals(1, firstBatch.size());
        assertTrue(secondScan.isEmpty());
        assertTrue(processedAsset.isEmpty());
    }

    @Test
    void modAnnouncementIncludesAutomaticUpdaterNoteAndDirectDownloadLink() {
        ReleaseService.PublishedRelease fabric = release(
                455025800L, "ArenaSystem Client", "Fabric"
        );
        ReleaseService.ReleaseAsset clientJar = new ReleaseService.ReleaseAsset(
                781772680L,
                "ArenaSystem_Client-0.1.5.jar",
                "uploaded",
                "https://github.com/example/repo/releases/download/Fabric/ArenaSystem_Client-0.1.5.jar",
                PUBLISHED_AT.plusSeconds(45)
        );
        ReleaseMonitor.PendingAsset pending =
                new ReleaseMonitor.PendingAsset(fabric, clientJar);

        String message = ReleaseMonitor.buildMessage(List.of(pending));

        assertTrue(message.contains(
                "MOD Client yang sudah kalian unduh akan otomatis update dalam beberapa menit " +
                        "karena sudah memiliki fitur auto updater, tapi kalau kalian ingin " +
                        "mengunduhnya langsung bisa lewat sini:"
        ));
        assertTrue(message.contains(clientJar.downloadUrl()));
    }

    @Test
    void pluginAnnouncementDoesNotShowClientUpdaterNote() {
        ReleaseService.PublishedRelease bukkit = release(
                455024822L, "ArenaSystem Server", "Bukkit"
        );
        ReleaseService.ReleaseAsset serverJar = new ReleaseService.ReleaseAsset(
                781850857L,
                "arenaSystem_Bukkit-0.2.8.9.jar",
                "uploaded",
                "https://github.com/example/repo/releases/download/Bukkit/arenaSystem_Bukkit-0.2.8.9.jar",
                PUBLISHED_AT.plusSeconds(45)
        );

        String message = ReleaseMonitor.buildMessage(List.of(
                new ReleaseMonitor.PendingAsset(bukkit, serverJar)
        ));

        assertFalse(message.contains("updater otomatis"));
        assertFalse(message.contains("mengunduhnya langsung"));
    }

    @Test
    void batchQuietPeriodIsFiveSecondsAndHasBoundedMaximumWindow() {
        assertEquals(5, ReleaseMonitor.BATCH_QUIET_PERIOD.toSeconds());
        assertEquals(20, ReleaseMonitor.MAX_BATCH_WINDOW.toSeconds());
    }

    @Test
    void detectsJarUploadedAfterReleasePublication() {
        ReleaseService.PublishedRelease release = release();
        ReleaseService.ReleaseAsset asset = asset(
                "arenasystem-server.jar",
                "uploaded",
                PUBLISHED_AT.plusSeconds(45)
        );

        assertTrue(ReleaseMonitor.isPostPublicationJar(release, asset));
    }

    @Test
    void ignoresJarUploadedBeforeReleasePublication() {
        ReleaseService.PublishedRelease release = release();
        ReleaseService.ReleaseAsset asset = asset(
                "arenasystem-server.jar",
                "uploaded",
                PUBLISHED_AT.minusSeconds(5)
        );

        assertFalse(ReleaseMonitor.isPostPublicationJar(release, asset));
    }

    @Test
    void ignoresNonJarAssets() {
        ReleaseService.PublishedRelease release = release();
        ReleaseService.ReleaseAsset asset = asset(
                "gradle.for.gpt.txt",
                "uploaded",
                PUBLISHED_AT.plusSeconds(45)
        );

        assertFalse(ReleaseMonitor.isPostPublicationJar(release, asset));
    }

    @Test
    void ignoresAssetsThatAreNotUploaded() {
        ReleaseService.PublishedRelease release = release();
        ReleaseService.ReleaseAsset asset = asset(
                "arenasystem-server.jar",
                "new",
                PUBLISHED_AT.plusSeconds(45)
        );

        assertFalse(ReleaseMonitor.isPostPublicationJar(release, asset));
    }

    private static ReleaseService.PublishedRelease release() {
        return release("ArenaSystem Server", "Bukkit");
    }

    private static ReleaseService.PublishedRelease release(String name, String tag) {
        return release(455024822L, name, tag);
    }

    private static ReleaseService.PublishedRelease release(
            long id,
            String name,
            String tag
    ) {
        return new ReleaseService.PublishedRelease(
                id,
                name,
                tag,
                PUBLISHED_AT,
                "",
                "https://github.com/example/repo/releases/tag/" + tag,
                List.of()
        );
    }

    private static ReleaseService.PublishedRelease releaseWithAsset(
            ReleaseService.PublishedRelease release,
            ReleaseService.ReleaseAsset asset
    ) {
        return new ReleaseService.PublishedRelease(
                release.id(),
                release.name(),
                release.tag(),
                release.publishedAt(),
                release.notes(),
                release.htmlUrl(),
                List.of(asset)
        );
    }

    private static ReleaseService.ReleaseAsset asset(String name) {
        return asset(781723501L, name);
    }

    private static ReleaseService.ReleaseAsset asset(long id, String name) {
        return asset(
                id,
                name,
                "uploaded",
                PUBLISHED_AT.plusSeconds(45)
        );
    }

    private static ReleaseService.ReleaseAsset asset(
            String name,
            String state,
            Instant createdAt
    ) {
        return asset(781723501L, name, state, createdAt);
    }

    private static ReleaseService.ReleaseAsset asset(
            long id,
            String name,
            String state,
            Instant createdAt
    ) {
        return new ReleaseService.ReleaseAsset(
                id,
                name,
                state,
                "https://github.com/example/repo/releases/download/Bukkit/" + name,
                createdAt
        );
    }
}

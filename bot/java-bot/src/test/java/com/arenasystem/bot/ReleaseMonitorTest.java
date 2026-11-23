package com.arenasystem.bot;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
        return new ReleaseService.PublishedRelease(
                455024822L,
                name,
                tag,
                PUBLISHED_AT,
                "",
                "https://github.com/example/repo/releases/tag/" + tag,
                List.of()
        );
    }

    private static ReleaseService.ReleaseAsset asset(
            String name,
            String state,
            Instant createdAt
    ) {
        return new ReleaseService.ReleaseAsset(
                781723501L,
                name,
                state,
                "https://github.com/example/repo/releases/download/Bukkit/" + name,
                createdAt
        );
    }
}

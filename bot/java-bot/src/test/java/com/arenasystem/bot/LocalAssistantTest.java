package com.arenasystem.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAssistantTest {
    @Test
    void answersArenaSystemOverviewFromLocalKnowledge() {
        LocalAssistant assistant = new LocalAssistant();

        String answer = assistant.answer(
                "test-chat:user:main",
                "Jelaskan apa itu ArenaSystem dan fitur utamanya"
        );

        assertTrue(answer.contains("Paper"), answer);
        assertTrue(answer.contains("Fabric"), answer);
        assertTrue(answer.contains("arena"), answer);
        assertTrue(answer.contains("Berdasarkan dokumentasi ArenaSystem"), answer);
        assertTrue(answer.contains("pertandingan"), answer);
    }

    @Test
    void returnsTheKnowledgeBoundaryForUndocumentedClassDetails() {
        LocalAssistant assistant = new LocalAssistant();

        String answer = assistant.answer(
                "test-chat:user:main",
                "Berapa damage dan cooldown skill Assassin?"
        );

        assertTrue(answer.contains("belum memuat uraian"), answer);
    }

    @Test
    void answersSimpleGreetings() {
        LocalAssistant assistant = new LocalAssistant();

        String answer = assistant.answer("test-chat:user:main", "Halo");

        assertTrue(answer.contains("Asisten ArenaSystem"), answer);
    }

    @Test
    void resetRemovesFollowUpContext() {
        LocalAssistant assistant = new LocalAssistant();
        String key = "test-chat:user:topic-42";

        assistant.answer(key, "Apa itu ArenaSystem?");
        String followUpWithContext = assistant.answer(key, "Bagaimana dengan itu?");
        assertTrue(followUpWithContext.contains("Berdasarkan dokumentasi ArenaSystem"),
                followUpWithContext);

        assistant.reset(key);

        String followUpAfterReset = assistant.answer(key, "Bagaimana dengan itu?");
        assertTrue(followUpAfterReset.contains("belum menemukan informasi yang cukup"),
                followUpAfterReset);
    }

    @Test
    void englishQuestionStillReceivesAnIndonesianAnswer() {
        LocalAssistant assistant = new LocalAssistant();

        String answer = assistant.answer(
                "test-chat:user:english",
                "Explain the main features of ArenaSystem"
        );

        assertTrue(answer.contains("Berdasarkan dokumentasi ArenaSystem"), answer);
        assertTrue(answer.contains("plugin server Paper"), answer);
        assertTrue(answer.contains("mod klien Fabric"), answer);
    }

    @Test
    void unrelatedQuestionDoesNotInventAnAnswer() {
        LocalAssistant assistant = new LocalAssistant();

        String answer = assistant.answer("test-chat:user:main", "Apa resep membuat rendang?");

        assertTrue(answer.contains("belum menemukan informasi yang cukup"), answer);
    }

    @Test
    void installationPhrasingRetrievesInstallationAndCompatibilityGuidance() {
        LocalAssistant assistant = new LocalAssistant();

        String answer = assistant.answer(
                "test-chat:user:installation",
                "Cara instal ArenaSystem"
        );

        assertTrue(answer.contains("Pemasangan dan kompatibilitas"), answer);
        assertTrue(answer.contains("Petunjuk pemasangan dan kompatibilitas versi"), answer);
    }

    @Test
    void releaseDownloadPhrasingRetrievesStableReleaseGuidance() {
        LocalAssistant assistant = new LocalAssistant();

        String answer = assistant.answer(
                "test-chat:user:downloads",
                "Di mana saya mengunduh rilis stabil terbaru?"
        );

        assertTrue(answer.contains("Rilis stabil dan unduhan"), answer);
        assertTrue(answer.contains("Halaman rilisnya tersedia"), answer);
    }

    @Test
    void searchAliasesAreNotExposedInUserFacingAnswers() {
        LocalAssistant assistant = new LocalAssistant();

        String answer = assistant.answer(
                "test-chat:user:hidden-aliases",
                "Cara instal ArenaSystem"
        );

        assertFalse(answer.contains("Pertanyaan terkait:"), answer);
        assertFalse(answer.contains("apa itu ArenaSystem"), answer);
    }
}

package com.arenasystem.bot;

import org.junit.jupiter.api.Test;

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
        assistant.reset(key);

        String followUp = assistant.answer(key, "Bagaimana cara memasangnya?");
        assertTrue(followUp.contains("belum menemukan informasi yang cukup"), followUp);
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
}

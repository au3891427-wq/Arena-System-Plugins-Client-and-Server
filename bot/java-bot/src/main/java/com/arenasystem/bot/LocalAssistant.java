package com.arenasystem.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Retrieval-based assistant. It does not call a language-model API.
 * BM25 ranks verified local knowledge sections; response text is composed
 * from those sections and therefore cannot invent undocumented project facts.
 */
public final class LocalAssistant {
    private record Document(String title, String body, String searchHints, String source) {}
    private record Ranked(Document document, double score, int matchedTerms) {}
    private record Conversation(String previousQuestion, String previousAnswer, long updatedAt) {}

    private static final double K1 = 1.5;
    private static final double B = 0.75;
    private static final double MINIMUM_SCORE = 0.55;
    private static final long MEMORY_TTL_MILLIS = 30 * 60 * 1000L;
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static final Set<String> STOP_WORDS = Set.of(
            "apa", "itu", "ini", "dan", "atau", "yang", "di", "ke", "dari", "untuk",
            "dengan", "pada", "adalah", "bagaimana", "mengapa", "kenapa", "siapa",
            "tolong", "jelaskan", "please", "what", "is", "are", "the", "a", "an",
            "and", "or", "how", "does", "do", "can", "could", "i", "you", "your",
            "about", "me", "my", "to", "of", "in", "on", "for", "with"
    );

    private static final Map<String, Set<String>> SYNONYMS = Map.ofEntries(
            Map.entry("arenasystem", Set.of("minecraft", "paper", "fabric", "plugin", "mod", "arena")),
            Map.entry("explain", Set.of("jelaskan", "gambaran", "overview")),
            Map.entry("project", Set.of("proyek", "minecraft", "paper", "fabric")),
            Map.entry("main", Set.of("utama", "pokok")),
            Map.entry("features", Set.of("fitur", "fungsi", "plugin", "mod", "kemampuan")),
            Map.entry("feature", Set.of("fitur", "fungsi", "plugin", "mod")),
            Map.entry("plugin", Set.of("server", "paper", "fitur")),
            Map.entry("mod", Set.of("klien", "client", "fabric")),
            Map.entry("match", Set.of("pertandingan", "arena")),
            Map.entry("matches", Set.of("pertandingan", "arena")),
            Map.entry("teams", Set.of("tim", "arena")),
            Map.entry("handshake", Set.of("koneksi", "protokol")),
            Map.entry("installation", Set.of("pemasangan", "cara")),
            Map.entry("compatibility", Set.of("kompatibilitas", "versi")),
            Map.entry("fitur", Set.of("feature", "features", "fungsi", "plugin", "mod")),
            Map.entry("utama", Set.of("main", "overview", "pokok")),
            Map.entry("server", Set.of("paper", "plugin")),
            Map.entry("client", Set.of("klien", "fabric", "mod")),
            Map.entry("klien", Set.of("client", "fabric", "mod")),
            Map.entry("kelas", Set.of("class", "classes", "role")),
            Map.entry("class", Set.of("kelas", "role")),
            Map.entry("skill", Set.of("ability", "abilities", "kemampuan")),
            Map.entry("kemampuan", Set.of("skill", "ability")),
            Map.entry("koneksi", Set.of("connection", "handshake", "protocol")),
            Map.entry("connection", Set.of("koneksi", "handshake")),
            Map.entry("update", Set.of("pembaruan", "updater", "rilis")),
            Map.entry("pembaruan", Set.of("update", "updater")),
            Map.entry("unduh", Set.of("download", "jar", "rilis")),
            Map.entry("download", Set.of("unduh", "jar", "release")),
            Map.entry("rilis", Set.of("release", "version")),
            Map.entry("instal", Set.of("install", "instalasi", "pemasangan", "installation")),
            Map.entry("instalasi", Set.of("installation", "install", "setup")),
            Map.entry("install", Set.of("instalasi", "installation", "setup")),
            Map.entry("kompatibilitas", Set.of("compatibility", "version")),
            Map.entry("versi", Set.of("version", "compatibility"))
    );

    private final List<Document> documents;
    private final Map<String, Conversation> conversations = new HashMap<>();

    public LocalAssistant() {
        this.documents = loadDocuments();
        if (documents.isEmpty()) {
            throw new IllegalStateException("Local ArenaSystem knowledge base is empty.");
        }
    }

    public synchronized String answer(String conversationKey, String question) {
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isBlank()) {
            return "Tulis pertanyaan setelah perintah /ask.";
        }
        if (normalizedQuestion.length() > 1500) {
            return "Pertanyaan terlalu panjang. Batasnya 1.500 karakter.";
        }

        long now = System.currentTimeMillis();
        pruneExpired(now);
        Conversation previous = conversations.get(conversationKey);
        String searchText = normalizedQuestion;
        if (isShortFollowUp(normalizedQuestion) && previous != null) {
            searchText = previous.previousQuestion() + " " +
                    previous.previousAnswer().substring(
                            0, Math.min(500, previous.previousAnswer().length())
                    ) + " " + normalizedQuestion;
        }

        String response = findAnswer(searchText);
        conversations.put(conversationKey, new Conversation(
                normalizedQuestion,
                response,
                now
        ));
        trimConversationCount();
        return response;
    }

    public synchronized void reset(String conversationKey) {
        conversations.remove(conversationKey);
    }

    private String findAnswer(String question) {
        String cleaned = normalize(question);
        if (isGreeting(cleaned)) {
            return "Halo! Aku Asisten ArenaSystem. Aku bisa membantu menjelaskan dokumentasi, komponen server/client, handshake, update, dan rilis stabil ArenaSystem.\n\nCoba tanyakan: Apa saja fitur utama ArenaSystem?";
        }

        List<String> originalTerms = tokenize(cleaned);
        if (originalTerms.isEmpty()) {
            return unknownAnswer();
        }

        Set<String> expandedTerms = expand(originalTerms);
        List<Ranked> ranked = rank(expandedTerms, originalTerms);
        if (ranked.isEmpty() || ranked.getFirst().score() < MINIMUM_SCORE) {
            return unknownAnswer();
        }

        Ranked best = ranked.getFirst();
        List<Ranked> selected = ranked.stream()
                .filter(item -> item.score() >= best.score() * 0.42)
                .limit(3)
                .toList();

        StringBuilder answer = new StringBuilder("🛡️ ASISTEN ARENASYSTEM\n\n");
        answer.append("Berdasarkan dokumentasi ArenaSystem yang tersedia:\n\n");

        for (int index = 0; index < selected.size(); index++) {
            Ranked item = selected.get(index);
            answer.append("• ").append(item.document().title()).append("\n");
            answer.append(wrap(item.document().body(), 850)).append("\n");
            if (index < selected.size() - 1) {
                answer.append("\n");
            }
        }

        LinkedHashSet<String> sources = selected.stream()
                .map(item -> item.document().source())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        answer.append("\nSumber: ").append(String.join(" | ", sources));
        answer.append("\n\nCatatan: Aku menggunakan pencarian dokumentasi lokal, bukan model generatif. Jika detailnya belum terdokumentasi, aku tidak akan menebaknya.");
        return answer.toString();
    }

    private List<Ranked> rank(Set<String> queryTerms, List<String> originalTerms) {
        int documentCount = documents.size();
        double averageLength = documents.stream()
                .mapToInt(document -> tokenize(
                        document.title() + " " + document.body() + " " + document.searchHints()
                ).size())
                .average()
                .orElse(1.0);

        Map<String, Integer> documentFrequency = new HashMap<>();
        List<List<String>> allTokens = new ArrayList<>();
        for (Document document : documents) {
            List<String> tokens = tokenize(
                    document.title() + " " + document.body() + " " + document.searchHints()
            );
            allTokens.add(tokens);
            for (String term : new HashSet<>(tokens)) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        List<Ranked> results = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            List<String> tokens = allTokens.get(index);
            Map<String, Integer> frequencies = new HashMap<>();
            for (String token : tokens) {
                frequencies.merge(token, 1, Integer::sum);
            }

            double score = 0.0;
            for (String term : queryTerms) {
                int frequency = frequencies.getOrDefault(term, 0);
                if (frequency == 0) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1.0 +
                        (documentCount - df + 0.5) / (df + 0.5));
                double denominator = frequency +
                        K1 * (1.0 - B + B * tokens.size() / Math.max(1.0, averageLength));
                score += idf * frequency * (K1 + 1.0) / denominator;
            }

            int matchedOriginalTerms = 0;
            for (String term : originalTerms) {
                if (frequencies.containsKey(term)) {
                    matchedOriginalTerms++;
                } else if (SYNONYMS.getOrDefault(term, Set.of()).stream()
                        .anyMatch(frequencies::containsKey)) {
                    matchedOriginalTerms++;
                }
            }

            if (score > 0.0) {
                results.add(new Ranked(documents.get(index), score, matchedOriginalTerms));
            }
        }

        results.sort(Comparator
                .comparingDouble(Ranked::score).reversed()
                .thenComparing(Comparator.comparingInt(Ranked::matchedTerms).reversed()));
        return results;
    }

    private static Set<String> expand(List<String> terms) {
        Set<String> expanded = new LinkedHashSet<>(terms);
        for (String term : terms) {
            expanded.addAll(SYNONYMS.getOrDefault(term, Set.of()));
        }
        return expanded;
    }

    private static List<String> tokenize(String input) {
        String normalized = normalize(input);
        List<String> tokens = new ArrayList<>();
        for (String token : SPLIT.split(normalized)) {
            if (!token.isBlank() && token.length() > 1 && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String normalize(String input) {
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT);
    }

    private static boolean isGreeting(String normalized) {
        return normalized.matches("^(halo|hai|hi|hello|pagi|siang|sore|malam)([.!? ]*)$");
    }

    private static boolean isShortFollowUp(String question) {
        List<String> terms = tokenize(question);
        String value = normalize(question);
        return terms.size() <= 5 ||
                value.matches(".*\\b(itu|tersebut|bagaimana kalau|lalu|kemudian)\\b.*");
    }

    private void pruneExpired(long now) {
        conversations.entrySet().removeIf(entry ->
                now - entry.getValue().updatedAt() > MEMORY_TTL_MILLIS);
    }

    private void trimConversationCount() {
        while (conversations.size() > 2000) {
            String oldest = conversations.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().updatedAt()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            conversations.remove(oldest);
        }
    }

    private static String wrap(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int split = text.lastIndexOf(' ', maxLength);
        if (split < maxLength / 2) {
            split = maxLength;
        }
        return text.substring(0, split).stripTrailing() + "…";
    }

    private static String unknownAnswer() {
        return "Aku belum menemukan informasi yang cukup di dokumentasi lokal ArenaSystem untuk menjawab pertanyaan itu dengan akurat.\n\nAku tidak akan mengarang detail. Coba persempit pertanyaan atau lihat dokumentasi rilis stabil:\nhttps://github.com/au3891427-wq/Arena-System-Plugins-Client-and-Server/releases";
    }

    private static List<Document> loadDocuments() {
        InputStream stream = LocalAssistant.class.getResourceAsStream("/knowledge/arenasystem.md");
        if (stream == null) {
            throw new IllegalStateException("Missing resource /knowledge/arenasystem.md.");
        }

        List<Document> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String title = "ArenaSystem";
            StringBuilder body = new StringBuilder();
            StringBuilder searchHints = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("## ")) {
                    addDocument(result, title, body, searchHints);
                    title = line.substring(3).trim();
                    body = new StringBuilder();
                    searchHints = new StringBuilder();
                } else if (line.startsWith("Pertanyaan terkait:")) {
                    searchHints.append(line.substring("Pertanyaan terkait:".length()).trim())
                            .append(' ');
                } else if (!line.startsWith("# ") && !line.isBlank()) {
                    body.append(line.trim()).append(' ');
                }
            }
            addDocument(result, title, body, searchHints);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read local ArenaSystem knowledge.", exception);
        }
        return List.copyOf(result);
    }

    private static void addDocument(
            List<Document> result,
            String title,
            StringBuilder body,
            StringBuilder searchHints
    ) {
        String content = body.toString().trim();
        if (!content.isBlank()) {
            result.add(new Document(
                    title,
                    content,
                    searchHints.toString().trim(),
                    "knowledge/arenasystem.md"
            ));
        }
    }
}

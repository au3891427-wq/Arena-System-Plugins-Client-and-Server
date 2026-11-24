package com.arenasystem.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class NineRouterClient {
    private static final String SYSTEM_PROMPT = """
            Kamu adalah Asisten ArenaSystem, asisten komunitas Minecraft yang menjawab dalam Bahasa Indonesia.

            Gunakan konteks dokumentasi ArenaSystem yang diberikan untuk menjawab pertanyaan tentang proyek.
            Konteks tersebut berasal dari pencarian dokumentasi lokal dan merupakan sumber fakta utama.

            Aturan akurasi:
            - Jangan mengarang fitur, detail implementasi, versi, kompatibilitas, statistik kelas, nilai damage,
              cooldown, aturan permainan, struktur paket, atau perilaku protokol yang tidak ada di konteks.
            - Jika konteks tidak mendukung jawaban spesifik tentang ArenaSystem, katakan dengan jelas bahwa
              dokumentasi yang tersedia belum menjelaskan detail tersebut.
            - Kamu boleh menjelaskan konsep teknis umum, tetapi bedakan dengan jelas dari implementasi ArenaSystem.
            - Jangan mengklaim sudah memeriksa server, repository, atau rilis secara langsung.
            - Pertahankan nama resmi teknologi, class, command, versi, dan istilah teknis.
            - Berikan jawaban yang jelas dan terstruktur, tanpa pembukaan bertele-tele.
            """;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI completionUri;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;

    public NineRouterClient(
            String baseUrl,
            String apiKey,
            String model,
            int timeoutSeconds,
            ObjectMapper mapper
    ) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.completionUri = URI.create(stripTrailingSlash(baseUrl) + "/chat/completions");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String answer(String question, String knowledgeContext)
            throws IOException, InterruptedException {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be blank.");
        }

        ObjectNode payload = mapper.createObjectNode()
                .put("model", model)
                .put("max_tokens", 1200)
                .put("stream", false);

        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", SYSTEM_PROMPT);

        String userContent = """
                Pertanyaan pengguna:
                %s

                Hasil pencarian dokumentasi lokal ArenaSystem:
                %s
                """.formatted(question, knowledgeContext == null ? "" : knowledgeContext);
        messages.addObject()
                .put("role", "user")
                .put("content", userContent);

        HttpRequest request = HttpRequest.newBuilder(completionUri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(payload),
                        StandardCharsets.UTF_8
                ))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Do not include upstream response bodies: gateways may echo private request data.
            throw new IOException("9Router returned HTTP " + response.statusCode() + ".");
        }

        JsonNode responseBody;
        try {
            responseBody = mapper.readTree(response.body());
        } catch (Exception exception) {
            throw new IOException("9Router returned invalid JSON.", exception);
        }

        JsonNode choices = responseBody.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("9Router response did not contain a completion choice.");
        }

        String content = extractContent(choices.get(0).path("message").path("content"));
        if (content.isBlank()) {
            throw new IOException("9Router returned an empty answer.");
        }
        return content;
    }

    private static String extractContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText().trim();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : content) {
                if (item.isTextual()) {
                    text.append(item.asText());
                } else if (item.hasNonNull("text")) {
                    text.append(item.path("text").asText());
                }
            }
            return text.toString().trim();
        }
        return "";
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isBlank()) {
            throw new IllegalArgumentException("9Router base URL must not be blank.");
        }
        return result;
    }
}

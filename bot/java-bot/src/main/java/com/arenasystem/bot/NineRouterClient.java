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
                // The public 9Router cloud endpoint may return SSE even when stream=false.
                // Request streaming explicitly and parse the completed SSE response below.
                .put("stream", true);

        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", SYSTEM_PROMPT);

        String context = knowledgeContext == null ? "" : knowledgeContext;
        context = context.replace(
                "\n\nCatatan: Aku menggunakan pencarian dokumentasi lokal, bukan model generatif. Jika detailnya belum terdokumentasi, aku tidak akan menebaknya.",
                ""
        );
        String userContent = """
                Pertanyaan pengguna:
                %s

                Hasil pencarian dokumentasi lokal ArenaSystem:
                %s
                """.formatted(question, context);
        messages.addObject()
                .put("role", "user")
                .put("content", userContent);

        HttpRequest request = HttpRequest.newBuilder(completionUri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
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
            throw new IOException(formatHttpError(response));
        }

        String content = extractCompletion(response.body());
        if (content.isBlank()) {
            throw new IOException("9Router returned an empty answer.");
        }
        return content;
    }

    private String formatHttpError(HttpResponse<String> response) {
        String details = safeErrorDetails(response.body());
        return "9Router returned HTTP " + response.statusCode()
                + (details.isBlank() ? "." : " (" + details + ").");
    }

    /**
     * Extracts only known, low-risk error fields. Arbitrary upstream messages are discarded
     * because they can contain echoed prompts, credentials, or other request data.
     */
    private String safeErrorDetails(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }

        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode error = root.path("error");
            if (!error.isObject()) {
                error = root;
            }

            String code = error.path("code").asText("");
            if (!code.matches("[A-Za-z0-9_.-]{1,80}")) {
                code = "";
            }

            String message = error.path("message").asText("").trim();
            String safeMessage = "";
            if (message.matches("(?i)The model [A-Za-z0-9._:/-]{1,100} does not exist\\.?")) {
                safeMessage = message;
            } else if (message.matches("(?i)Unknown model: [A-Za-z0-9._:/-]{1,100}")) {
                safeMessage = message;
            } else if (message.matches("(?i)No active credentials for provider: [A-Za-z0-9._-]{1,80}")) {
                safeMessage = message;
            } else if (message.matches("(?i)Invalid API key\\.?")
                    || message.matches("(?i)Invalid API key or access token\\.?")
                    || message.matches("(?i)Unauthorized\\.?")) {
                safeMessage = message;
            }

            if (!code.isBlank()) {
                return code + (safeMessage.isBlank() ? "" : ": " + safeMessage);
            }
            return safeMessage;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String extractCompletion(String responseBody) throws IOException {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IOException("9Router returned an empty response.");
        }

        // Keep compatibility with OpenAI-compatible gateways that still answer with JSON.
        try {
            JsonNode json = mapper.readTree(responseBody);
            if (json != null && !json.isMissingNode()) {
                JsonNode choices = json.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    throw new IOException("9Router response did not contain a completion choice.");
                }
                return extractContent(choices.get(0).path("message").path("content"));
            }
        } catch (IOException exception) {
            if ("9Router response did not contain a completion choice.".equals(exception.getMessage())) {
                throw exception;
            }
            // A streaming response is a sequence of SSE data records, not one JSON document.
        }

        return extractSseContent(responseBody);
    }

    private String extractSseContent(String responseBody) throws IOException {
        StringBuilder answer = new StringBuilder();
        boolean foundDataRecord = false;

        for (String rawLine : responseBody.split("\\R")) {
            String line = rawLine.strip();
            if (!line.startsWith("data:")) {
                continue;
            }

            String data = line.substring("data:".length()).strip();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                continue;
            }

            foundDataRecord = true;
            final JsonNode event;
            try {
                event = mapper.readTree(data);
            } catch (Exception exception) {
                throw new IOException("9Router returned malformed streaming data.", exception);
            }

            JsonNode choices = event.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                continue;
            }

            JsonNode choice = choices.get(0);
            JsonNode deltaContent = choice.path("delta").path("content");
            String chunk = extractContent(deltaContent);
            if (chunk.isEmpty()) {
                // Some compatible gateways send a complete message object in an SSE event.
                chunk = extractContent(choice.path("message").path("content"));
            }
            answer.append(chunk);
        }

        if (!foundDataRecord) {
            throw new IOException("9Router returned neither JSON nor a valid streaming response.");
        }
        return answer.toString().trim();
    }

    private static String extractContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
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
            return text.toString();
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

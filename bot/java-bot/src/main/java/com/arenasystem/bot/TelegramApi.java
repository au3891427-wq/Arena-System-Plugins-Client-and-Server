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
import java.time.Duration;

public final class TelegramApi {
    private static final String API_BASE = "https://api.telegram.org/bot";
    private final String token;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public TelegramApi(String token, ObjectMapper mapper) {
        this.token = token;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public JsonNode call(String method, ObjectNode payload) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + token + "/" + method))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        JsonNode result;
        try {
            result = mapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IOException("Telegram API returned invalid JSON.", exception);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300
                || !result.path("ok").asBoolean(false)) {
            String description = result.path("description").asText("HTTP " + response.statusCode());
            throw new IOException("Telegram " + method + " failed: " + description);
        }
        return result.path("result");
    }

    public void sendMessage(long chatId, String text, Long threadId, JsonNode replyMarkup)
            throws IOException, InterruptedException {
        ObjectNode payload = mapper.createObjectNode()
                .put("chat_id", chatId)
                .put("text", text)
                .put("disable_web_page_preview", true);
        if (threadId != null) {
            payload.put("message_thread_id", threadId);
        }
        if (replyMarkup != null) {
            payload.set("reply_markup", replyMarkup);
        }
        call("sendMessage", payload);
    }

    public void sendChatAction(long chatId, String action, Long threadId)
            throws IOException, InterruptedException {
        ObjectNode payload = mapper.createObjectNode()
                .put("chat_id", chatId)
                .put("action", action);
        if (threadId != null) {
            payload.put("message_thread_id", threadId);
        }
        call("sendChatAction", payload);
    }

    public void answerCallbackQuery(String callbackQueryId) {
        try {
            call("answerCallbackQuery", mapper.createObjectNode()
                    .put("callback_query_id", callbackQueryId)
                    .put("cache_time", 0));
        } catch (Exception exception) {
            System.getLogger(TelegramApi.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "Could not acknowledge Telegram callback query: " + exception.getMessage()
            );
        }
    }

    public void registerCommands() throws IOException, InterruptedException {
        ArrayNode commands = mapper.createArrayNode();
        addCommand(commands, "start", "Buka asisten ArenaSystem");
        addCommand(commands, "help", "Tampilkan perintah dan bantuan");
        addCommand(commands, "latest", "Tampilkan rilis stabil terbaru");
        addCommand(commands, "downloads", "Lihat unduhan JAR stabil");
        addCommand(commands, "ask", "Tanyakan sesuatu kepada asisten lokal");
        addCommand(commands, "reset", "Hapus konteks percakapan singkat");
        addCommand(commands, "privacy", "Baca informasi privasi percakapan");
        ObjectNode payload = mapper.createObjectNode();
        payload.set("commands", commands);
        call("setMyCommands", payload);
    }

    private void addCommand(ArrayNode commands, String command, String description) {
        commands.add(mapper.createObjectNode()
                .put("command", command)
                .put("description", description));
    }

    public void registerWebhook(String publicBaseUrl, String secret)
            throws IOException, InterruptedException {
        ArrayNode allowedUpdates = mapper.createArrayNode()
                .add("message")
                .add("callback_query");
        ObjectNode payload = mapper.createObjectNode()
                .put("url", publicBaseUrl + "/telegram/webhook")
                .put("secret_token", secret)
                .put("max_connections", 20);
        payload.set("allowed_updates", allowedUpdates);
        call("setWebhook", payload);
    }
}

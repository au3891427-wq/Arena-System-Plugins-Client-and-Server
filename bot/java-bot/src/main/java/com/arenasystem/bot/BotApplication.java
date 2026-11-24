package com.arenasystem.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BotApplication {
    private static final System.Logger LOGGER = System.getLogger(BotApplication.class.getName());
    private static final String WEBHOOK_PATH = "/telegram/webhook";
    private static final String ANNOUNCEMENT_PATH = "/api/announcement";
    private static final int MAX_ANNOUNCEMENT_BYTES = 16 * 1024;
    private static final int ANNOUNCEMENT_REQUESTS_PER_MINUTE = 5;
    private static final int MAX_WEBHOOK_BYTES = 1024 * 1024;
    private static final int MAX_MESSAGE_LENGTH = 3900;
    private static final int MAX_QUESTION_LENGTH = 1500;
    private static final long RATE_WINDOW_MILLIS = 60_000L;
    private static final int USER_REQUESTS_PER_MINUTE = 5;
    private static final int GLOBAL_REQUESTS_PER_MINUTE = 40;
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "^/([a-z0-9_]+)(?:@[a-z0-9_]+)?(?:\\s+([\\s\\S]*))?$",
            Pattern.CASE_INSENSITIVE
    );

    private final AppConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TelegramApi telegram;
    private final ReleaseService releases;
    private final ReleaseMonitor releaseMonitor;
    private final LocalAssistant assistant = new LocalAssistant();
    private final NineRouterClient nineRouter;
    private final HttpServer server;
    private final ExecutorService httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Long> inFlightUpdates = ConcurrentHashMap.newKeySet();
    private final Set<String> allowedChats;
    private final ConcurrentHashMap<Long, List<Long>> userRequestTimes = new ConcurrentHashMap<>();
    private final List<Long> globalRequestTimes = new ArrayList<>();
    private final List<Long> announcementRequestTimes = new ArrayList<>();
    private final Set<Long> processedUpdates = new LinkedHashSet<>();

    private BotApplication(AppConfig config) throws IOException {
        this.config = config;
        this.nineRouter = config.nineRouterEnabled()
                ? new NineRouterClient(
                        config.nineRouterBaseUrl(),
                        config.nineRouterApiKey(),
                        config.nineRouterModel(),
                        config.nineRouterTimeoutSeconds(),
                        mapper
                )
                : null;
        this.telegram = new TelegramApi(config.telegramBotToken(), mapper);
        this.releases = new ReleaseService(config.githubRepository(), mapper);
        this.releaseMonitor = new ReleaseMonitor(
                releases,
                telegram,
                mapper,
                Path.of(config.releaseMonitorStateFile()),
                config.releaseMonitorIntervalSeconds()
        );
        this.allowedChats = config.allowedChatIds();
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", config.port()), 0);
        this.server.setExecutor(httpExecutor);
        this.server.createContext("/healthz", this::handleHealth);
        this.server.createContext(ANNOUNCEMENT_PATH, this::handleAnnouncement);
        this.server.createContext(WEBHOOK_PATH, this::handleWebhook);
    }

    public static void main(String[] args) throws Exception {
        BotApplication app = new BotApplication(AppConfig.fromEnvironment());
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
        app.start();
    }

    private void start() {
        server.start();
        LOGGER.log(System.Logger.Level.INFO, "ArenaSystem Java bot listening on port " + config.port());
        if (config.nineRouterEnabled()) {
            LOGGER.log(System.Logger.Level.INFO, "9Router AI assistant enabled.");
        } else {
            LOGGER.log(System.Logger.Level.WARNING,
                    "9Router is not configured; /ask is using local BM25 answers.");
        }

        try {
            telegram.registerCommands();
            LOGGER.log(System.Logger.Level.INFO, "Telegram commands registered.");
        } catch (Exception exception) {
            LOGGER.log(System.Logger.Level.ERROR,
                    "Could not register Telegram commands: " + exception.getMessage());
        }

        if (!config.publicBaseUrl().isBlank()) {
            try {
                telegram.registerWebhook(config.publicBaseUrl(), config.telegramWebhookSecret());
                LOGGER.log(System.Logger.Level.INFO, "Telegram webhook registered.");
            } catch (Exception exception) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "Could not register Telegram webhook: " + exception.getMessage());
            }
        } else {
            LOGGER.log(System.Logger.Level.WARNING,
                    "PUBLIC_BASE_URL is empty; Telegram webhook was not registered.");
        }

        releaseMonitor.start();
    }

    private void stop() {
        releaseMonitor.stop();
        server.stop(1);
        requestExecutor.shutdown();
        httpExecutor.shutdown();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())
                || !"/healthz".equals(exchange.getRequestURI().getPath())) {
            sendHttp(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        ObjectNode status = mapper.createObjectNode()
                .put("status", "ok")
                .put("service", "arenasystem-telegram-bot")
                .put("language", "Java 21")
                .put("assistant", config.nineRouterEnabled() ? "9router+bm25" : "local-bm25")
                .put("ai_enabled", config.nineRouterEnabled())
                .put("announcement_api_enabled", !config.announcementApiKey().isBlank());
        sendHttp(exchange, 200, mapper.writeValueAsString(status),
                "application/json; charset=utf-8");
    }


    private void handleAnnouncement(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())
                || !ANNOUNCEMENT_PATH.equals(exchange.getRequestURI().getPath())) {
            sendHttp(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }

        if (config.announcementApiKey().isBlank()) {
            sendAnnouncementResponse(exchange, 503, false,
                    "Announcement API is not configured.");
            return;
        }

        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || !constantTimeEquals(config.announcementApiKey(), authorization.substring(7).trim())) {
            sendAnnouncementResponse(exchange, 401, false, "Unauthorized.");
            return;
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            sendAnnouncementResponse(exchange, 415, false, "Content-Type must be application/json.");
            return;
        }

        byte[] body;
        try (var input = exchange.getRequestBody()) {
            body = input.readNBytes(MAX_ANNOUNCEMENT_BYTES + 1);
        }
        if (body.length > MAX_ANNOUNCEMENT_BYTES) {
            sendAnnouncementResponse(exchange, 413, false, "Request body is too large.");
            return;
        }

        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (Exception exception) {
            sendAnnouncementResponse(exchange, 400, false, "Invalid JSON.");
            return;
        }
        if (payload == null || !payload.path("text").isTextual()) {
            sendAnnouncementResponse(exchange, 400, false, "JSON field 'text' must be a string.");
            return;
        }

        final String announcement;
        try {
            announcement = AnnouncementText.normalize(payload.path("text").asText());
        } catch (IllegalArgumentException exception) {
            sendAnnouncementResponse(exchange, 400, false, exception.getMessage());
            return;
        }

        if (!consumeAnnouncementRateLimit()) {
            sendAnnouncementResponse(exchange, 429, false,
                    "Rate limit exceeded. Wait one minute before sending another announcement.");
            return;
        }

        try {
            releaseMonitor.sendManualAnnouncement(announcement);
            sendAnnouncementResponse(exchange, 200, true,
                    "Announcement sent to the configured Telegram topic.");
        } catch (IllegalStateException exception) {
            sendAnnouncementResponse(exchange, 409, false, exception.getMessage());
        } catch (Exception exception) {
            LOGGER.log(System.Logger.Level.ERROR,
                    "Manual Telegram announcement failed: " + exception.getMessage());
            sendAnnouncementResponse(exchange, 502, false,
                    "Telegram delivery failed. Check the bot logs and try again.");
        }
    }

    private void sendAnnouncementResponse(
            HttpExchange exchange,
            int status,
            boolean ok,
            String message
    ) throws IOException {
        ObjectNode response = mapper.createObjectNode()
                .put("ok", ok)
                .put("message", message);
        sendHttp(exchange, status, mapper.writeValueAsString(response),
                "application/json; charset=utf-8");
    }

    private boolean consumeAnnouncementRateLimit() {
        long now = System.currentTimeMillis();
        synchronized (announcementRequestTimes) {
            announcementRequestTimes.removeIf(time -> now - time >= RATE_WINDOW_MILLIS);
            if (announcementRequestTimes.size() >= ANNOUNCEMENT_REQUESTS_PER_MINUTE) {
                return false;
            }
            announcementRequestTimes.add(now);
            return true;
        }
    }

    private void handleWebhook(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())
                || !WEBHOOK_PATH.equals(exchange.getRequestURI().getPath())) {
            sendHttp(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }

        String suppliedSecret = exchange.getRequestHeaders().getFirst(
                "X-Telegram-Bot-Api-Secret-Token"
        );
        if (!constantTimeEquals(config.telegramWebhookSecret(), suppliedSecret)) {
            sendHttp(exchange, 401, "Unauthorized", "text/plain; charset=utf-8");
            return;
        }

        byte[] body;
        try (var input = exchange.getRequestBody()) {
            body = input.readNBytes(MAX_WEBHOOK_BYTES + 1);
        }
        if (body.length > MAX_WEBHOOK_BYTES) {
            sendHttp(exchange, 413, "Payload too large", "text/plain; charset=utf-8");
            return;
        }

        JsonNode update;
        try {
            update = mapper.readTree(body);
        } catch (Exception exception) {
            sendHttp(exchange, 400, "Invalid JSON", "text/plain; charset=utf-8");
            return;
        }
        if (update == null || !update.path("update_id").canConvertToLong()) {
            sendHttp(exchange, 400, "Invalid Telegram update", "text/plain; charset=utf-8");
            return;
        }

        long updateId = update.path("update_id").asLong();
        synchronized (processedUpdates) {
            if (processedUpdates.contains(updateId) || inFlightUpdates.contains(updateId)) {
                sendHttp(exchange, 200, "OK", "text/plain; charset=utf-8");
                return;
            }
            inFlightUpdates.add(updateId);
        }

        sendHttp(exchange, 200, "OK", "text/plain; charset=utf-8");
        requestExecutor.execute(() -> {
            try {
                processUpdate(update);
                synchronized (processedUpdates) {
                    processedUpdates.add(updateId);
                    while (processedUpdates.size() > 4096) {
                        Long oldest = processedUpdates.iterator().next();
                        processedUpdates.remove(oldest);
                    }
                }
            } catch (Exception exception) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "Telegram update failed: " + exception.getMessage());
            } finally {
                inFlightUpdates.remove(updateId);
            }
        });
    }

    private void processUpdate(JsonNode update) throws Exception {
        if (update.has("callback_query")) {
            handleCallback(update.path("callback_query"));
        } else if (update.has("message")) {
            handleMessage(update.path("message"));
        }
    }

    private void handleMessage(JsonNode message) throws Exception {
        JsonNode chat = message.path("chat");
        JsonNode from = message.path("from");
        if (!chat.path("id").canConvertToLong()
                || !from.path("id").canConvertToLong()
                || from.path("is_bot").asBoolean(false)) {
            return;
        }

        long chatId = chat.path("id").asLong();
        long userId = from.path("id").asLong();
        if (!isChatAllowed(chatId)) {
            return;
        }

        String text = message.path("text").asText("").trim();
        if (text.isBlank()) {
            return;
        }

        Long threadId = message.path("message_thread_id").isNumber()
                ? message.path("message_thread_id").asLong()
                : null;

        Matcher matcher = COMMAND_PATTERN.matcher(text);
        if (matcher.matches()) {
            handleCommand(matcher.group(1).toLowerCase(Locale.ROOT),
                    matcher.group(2) == null ? "" : matcher.group(2).trim(),
                    chatId, userId, threadId);
            return;
        }

        if ("private".equals(chat.path("type").asText())) {
            answerQuestion(chatId, userId, threadId, text);
            return;
        }

        if (message.path("reply_to_message").path("from").path("is_bot").asBoolean(false)) {
            answerQuestion(chatId, userId, threadId, text);
        }
    }

    private void handleCommand(
            String command,
            String argument,
            long chatId,
            long userId,
            Long threadId
    ) throws Exception {
        switch (command) {
            case "start", "menu" -> {
                if ("ask".equalsIgnoreCase(argument)) {
                    reply(chatId,
                            "🤖 Asisten lokal ArenaSystem siap. Kirim pertanyaan di chat ini.",
                            threadId, null);
                } else {
                    sendWelcome(chatId, threadId);
                }
            }
            case "help" -> sendHelp(chatId, threadId);
            case "latest", "status" -> sendLatestRelease(chatId, threadId, false);
            case "download", "downloads" -> sendLatestRelease(chatId, threadId, true);
            case "ask" -> {
                if (argument.isBlank()) {
                    reply(chatId,
                            "🤖 Kirim pertanyaan seperti ini:\n/ask Apa fungsi handshake ArenaSystem?",
                            threadId, null);
                } else {
                    answerQuestion(chatId, userId, threadId, argument);
                }
            }
            case "reset" -> {
                assistant.reset(conversationKey(chatId, userId, threadId));
                reply(chatId, "🧹 Konteks percakapan singkat sudah dihapus.", threadId, null);
            }
            case "privacy" -> reply(chatId, privacyText(), threadId, null);
            case "setreleasechannel" -> setReleaseChannel(chatId, userId, threadId);
            default -> sendHelp(chatId, threadId);
        }
    }

    private void setReleaseChannel(long chatId, long userId, Long threadId)
            throws Exception {
        if (threadId == null) {
            reply(chatId,
                    "Perintah ini harus dijalankan di dalam topik grup Telegram yang akan menerima pengumuman.",
                    threadId, null);
            return;
        }

        boolean administrator;
        try {
            administrator = telegram.isChatAdministrator(chatId, userId);
        } catch (Exception exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not verify Telegram administrator status: " + exception.getMessage());
            reply(chatId,
                    "Status admin belum dapat diverifikasi. Pastikan bot menjadi admin grup, lalu coba lagi.",
                    threadId, null);
            return;
        }

        if (!administrator) {
            reply(chatId,
                    "Hanya admin grup yang dapat mengatur topik pengumuman rilis.",
                    threadId, null);
            return;
        }

        releaseMonitor.setAnnouncementTarget(chatId, threadId);
        reply(chatId,
                "✅ Topik ini berhasil ditetapkan sebagai tujuan pengumuman Release ArenaSystem. " +
                        "Bot akan memeriksa asset JAR baru secara otomatis.",
                threadId, null);
    }

    private void answerQuestion(long chatId, long userId, Long threadId, String question)
            throws Exception {
        if (question.isBlank() || question.length() > MAX_QUESTION_LENGTH) {
            reply(chatId, "Pertanyaan harus berisi 1-" + MAX_QUESTION_LENGTH + " karakter.",
                    threadId, null);
            return;
        }
        String rateLimit = consumeRateLimit(userId);
        if (rateLimit != null) {
            reply(chatId, rateLimit, threadId, null);
            return;
        }
        telegram.sendChatAction(chatId, "typing", threadId);
        String localAnswer = assistant.answer(conversationKey(chatId, userId, threadId), question);
        String answer = localAnswer;
        if (nineRouter != null) {
            try {
                answer = nineRouter.answer(question, localAnswer);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOGGER.log(System.Logger.Level.WARNING,
                        "9Router request was interrupted; using local BM25 answer.");
                answer = aiFallbackAnswer(localAnswer);
            } catch (Exception exception) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "9Router request failed; using local BM25 answer: " +
                                describeNineRouterFailure(exception));
                answer = aiFallbackAnswer(localAnswer);
            }
        }
        reply(chatId, answer, threadId, null);
    }

    /**
     * Reports only safe, client-generated diagnostics. Never logs upstream response bodies,
     * request headers, API keys, prompts, or provider credentials.
     */
    private static String describeNineRouterFailure(Exception exception) {
        String message = exception.getMessage();
        if (message != null && (
                message.matches("9Router returned HTTP [1-5]\\d{2}\\.")
                        || message.equals("9Router returned an empty answer.")
                        || message.equals("9Router returned an empty response.")
                        || message.equals("9Router response did not contain a completion choice.")
                        || message.equals("9Router returned malformed streaming data.")
                        || message.equals("9Router returned neither JSON nor a valid streaming response.")
        )) {
            return message;
        }

        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        return rootCause.getClass().getSimpleName();
    }

    private static String aiFallbackAnswer(String localAnswer) {
        return "⚠️ Layanan AI 9Router sedang tidak tersedia. Berikut hasil pencarian dokumentasi lokal:\n\n" +
                localAnswer;
    }

    private void handleCallback(JsonNode callback) throws Exception {
        String callbackId = callback.path("id").asText("");
        if (!callbackId.isBlank()) {
            telegram.answerCallbackQuery(callbackId);
        }

        JsonNode message = callback.path("message");
        if (!message.path("chat").path("id").canConvertToLong()) {
            return;
        }
        long chatId = message.path("chat").path("id").asLong();
        if (!isChatAllowed(chatId)) {
            return;
        }

        Long threadId = message.path("message_thread_id").isNumber()
                ? message.path("message_thread_id").asLong()
                : null;
        switch (callback.path("data").asText("")) {
            case "menu:home" -> sendWelcome(chatId, threadId);
            case "menu:latest" -> sendLatestRelease(chatId, threadId, false);
            case "menu:downloads" -> sendLatestRelease(chatId, threadId, true);
            case "menu:help" -> sendHelp(chatId, threadId);
            case "menu:ask" -> reply(chatId,
                    "🤖 Kirim pertanyaan di chat pribadi, atau gunakan /ask <pertanyaan> di topik grup.",
                    threadId, null);
            default -> reply(chatId, "Aksi menu tidak tersedia. Gunakan /start.",
                    threadId, null);
        }
    }

    private void sendWelcome(long chatId, Long threadId) throws Exception {
        String text = String.join("\n",
                "🛡️ ARENASYSTEM",
                "ASISTEN KOMUNITAS RESMI",
                "",
                "Asisten komunitas untuk dokumentasi ArenaSystem, rilis stabil, dan unduhan.",
                "",
                "Pilih menu di bawah untuk memulai."
        );
        reply(chatId, text, threadId, mainMenuKeyboard());
    }

    private void sendHelp(long chatId, Long threadId) throws Exception {
        String text = String.join("\n",
                "📚 PUSAT BANTUAN ARENASYSTEM",
                "",
                "/start — Buka menu utama",
                "/latest — Rilis stabil terbaru",
                "/downloads — Unduh file JAR",
                "/ask <pertanyaan> — Tanya asisten lokal",
                "/reset — Hapus konteks percakapan",
                "/privacy — Informasi privasi",
                "/setreleasechannel — Atur topik pengumuman (admin grup)",
                "",
                "Jalankan /setreleasechannel di topik grup yang akan menerima pengumuman.",
                "Di chat pribadi, kamu juga bisa mengirim pertanyaan langsung.",
                "Di topic grup, gunakan /ask <pertanyaan> atau balas pesan bot."
        );
        reply(chatId, text, threadId, helpKeyboard());
    }

    private void sendLatestRelease(long chatId, Long threadId, boolean downloadsOnly)
            throws Exception {
        Optional<ReleaseService.Release> latest;
        try {
            latest = releases.latestStable();
        } catch (Exception exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "GitHub release lookup failed: " + exception.getMessage());
            reply(chatId,
                    "Rilis stabil belum dapat diperiksa sekarang. Coba lagi nanti.\n\n" +
                            releases.releasesUrl(),
                    threadId, null);
            return;
        }

        if (latest.isEmpty()) {
            reply(chatId,
                    "📦 RILIS STABIL ARENASYSTEM\n\nBelum ada rilis stabil dengan file JAR yang diunggah.\n\n" +
                            releases.releasesUrl(),
                    threadId, null);
            return;
        }

        ReleaseService.Release release = latest.get();
        StringBuilder text = new StringBuilder(downloadsOnly
                ? "⬇️ UNDUHAN ARENASYSTEM\n\n"
                : "📦 ARENASYSTEM — RILIS STABIL TERBARU\n\n");
        text.append(release.name()).append("\n");
        text.append("Versi: ").append(release.tag()).append("\n");
        text.append("Diterbitkan: ").append(formatDate(release.publishedAt())).append("\n");

        if (!downloadsOnly) {
            text.append("\nCatatan rilis:\n");
            String notes = release.notes().isBlank() ? "Catatan rilis belum tersedia." : release.notes();
            text.append(notes, 0, Math.min(notes.length(), 1800));
        } else {
            text.append("\nBerkas JAR tersedia:\n");
            for (ReleaseService.Asset asset : release.assets().stream().limit(8).toList()) {
                text.append("• ").append(asset.name()).append("\n");
            }
        }

        ArrayNode rows = mapper.createArrayNode();
        for (ReleaseService.Asset asset : release.assets().stream().limit(6).toList()) {
            ArrayNode row = mapper.createArrayNode();
            row.add(mapper.createObjectNode()
                    .put("text", "⬇️ " + clip(asset.name(), 42))
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

        ObjectNode markup = mapper.createObjectNode();
        markup.set("inline_keyboard", rows);
        reply(chatId, text.toString(), threadId, markup);
    }

    private void reply(long chatId, String text, Long threadId, JsonNode replyMarkup)
            throws Exception {
        List<String> parts = splitMessage(text);
        for (int index = 0; index < parts.size(); index++) {
            telegram.sendMessage(chatId, parts.get(index), threadId,
                    index == 0 ? replyMarkup : null);
        }
    }

    private String consumeRateLimit(long userId) {
        long now = System.currentTimeMillis();
        synchronized (globalRequestTimes) {
            List<Long> userTimes = userRequestTimes.computeIfAbsent(
                    userId, ignored -> new ArrayList<>()
            );
            userTimes.removeIf(time -> now - time >= RATE_WINDOW_MILLIS);
            globalRequestTimes.removeIf(time -> now - time >= RATE_WINDOW_MILLIS);

            if (userTimes.size() >= USER_REQUESTS_PER_MINUTE) {
                return "Batas pertanyaanmu tercapai. Coba lagi dalam satu menit.";
            }
            if (globalRequestTimes.size() >= GLOBAL_REQUESTS_PER_MINUTE) {
                return "Asisten sedang ramai. Coba lagi sebentar.";
            }

            userTimes.add(now);
            globalRequestTimes.add(now);
            if (userRequestTimes.size() > 5000) {
                userRequestTimes.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            }
            return null;
        }
    }

    private boolean isChatAllowed(long chatId) {
        return allowedChats.isEmpty() || allowedChats.contains(Long.toString(chatId));
    }

    private static String conversationKey(long chatId, long userId, Long threadId) {
        return chatId + ":" + userId + ":" + (threadId == null ? "main" : threadId);
    }

    private String privacyText() {
        String aiProcessing = config.nineRouterEnabled()
                ? "Pertanyaan /ask dikirim ke 9Router melalui HTTPS bersama hasil pencarian dokumentasi lokal. Pemrosesan selanjutnya mengikuti model/provider yang dipilih di akun 9Router."
                : "Pertanyaan diproses secara lokal menggunakan mesin pencarian dokumentasi BM25. 9Router belum dikonfigurasi.";
        return String.join("\n",
                "🔒 PRIVASI",
                "",
                aiProcessing,
                "Konteks percakapan singkat disimpan di memori proses hingga 30 menit tanpa aktivitas dan dapat dihapus dengan /reset.",
                "Konteks hilang saat layanan dimulai ulang.",
                "Untuk fitur rilis dan unduhan, bot mengakses GitHub Releases API.",
                "",
                "Jangan kirim kata sandi, token bot, API key, atau rahasia pribadi."
        );
    }

    private ObjectNode mainMenuKeyboard() {
        ArrayNode rows = mapper.createArrayNode();
        rows.add(buttonRow(
                button("📦 Rilis Terbaru", "menu:latest"),
                button("⬇️ Unduhan", "menu:downloads")
        ));
        rows.add(buttonRow(
                button("🤖 Tanya Asisten", "menu:ask"),
                button("📚 Pusat Bantuan", "menu:help")
        ));
        ObjectNode markup = mapper.createObjectNode();
        markup.set("inline_keyboard", rows);
        return markup;
    }

    private ObjectNode helpKeyboard() {
        ArrayNode rows = mapper.createArrayNode();
        rows.add(buttonRow(
                button("📦 Rilis Terbaru", "menu:latest"),
                button("⬇️ Unduhan", "menu:downloads")
        ));
        rows.add(buttonRow(
                button("🤖 Tanya Asisten", "menu:ask"),
                button("🏠 Menu Utama", "menu:home")
        ));
        ObjectNode markup = mapper.createObjectNode();
        markup.set("inline_keyboard", rows);
        return markup;
    }

    private ObjectNode button(String text, String callbackData) {
        return mapper.createObjectNode()
                .put("text", text)
                .put("callback_data", callbackData);
    }

    private ArrayNode buttonRow(ObjectNode first, ObjectNode second) {
        return mapper.createArrayNode().add(first).add(second);
    }

    private static List<String> splitMessage(String input) {
        if (input.length() <= MAX_MESSAGE_LENGTH) {
            return List.of(input);
        }
        List<String> parts = new ArrayList<>();
        String remaining = input;
        while (remaining.length() > MAX_MESSAGE_LENGTH) {
            int split = remaining.lastIndexOf('\n', MAX_MESSAGE_LENGTH);
            if (split < MAX_MESSAGE_LENGTH / 2) {
                split = MAX_MESSAGE_LENGTH;
            }
            parts.add(remaining.substring(0, split).stripTrailing());
            remaining = remaining.substring(split).stripLeading();
        }
        if (!remaining.isEmpty()) {
            parts.add(remaining);
        }
        return parts;
    }

    private static String clip(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(1, maxLength - 1)).stripTrailing() + "…";
    }

    private static String formatDate(String value) {
        if (value == null || value.isBlank()) {
            return "Not specified";
        }
        try {
            return Instant.parse(value).toString().substring(0, 10);
        } catch (Exception ignored) {
            return value;
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void sendHttp(
            HttpExchange exchange,
            int status,
            String body,
            String contentType
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

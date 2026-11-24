# ArenaSystem Telegram Bot — Java Edition

A Java 21 Telegram bot with a retrieval-grounded ArenaSystem assistant. It uses local BM25 search and optionally 9Router through an OpenAI-compatible API.

## What it does

- /start opens the Telegram menu.
- /latest shows the newest published stable GitHub Release that contains an uploaded JAR.
- /downloads lists JAR assets and provides direct download buttons.
- /ask <question> searches the local ArenaSystem knowledge base using BM25, then can generate an Indonesian answer through the OpenAI-compatible 9Router API.
- Private chats can send questions directly; in group topics use /ask <question> or reply to a bot message.
- /reset clears the user's short conversation context.
- /privacy describes local processing and in-memory context.
- /setreleasechannel configures the current Telegram forum topic as the destination for later JAR upload announcements and manually submitted announcements (group administrators only).
- `POST /api/announcement` accepts authenticated manual announcements and forwards them to the configured Telegram topic.
- GitHub Actions announces stable Release publication. The Java service polls GitHub Releases for JAR assets uploaded after publication and announces each new asset once.

## Bahasa jawaban

Semua jawaban dari fitur /ask disusun dalam Bahasa Indonesia. Pertanyaan berbahasa Inggris tetap dapat dikenali melalui kamus sinonim, tetapi konten jawaban tetap diambil dari basis pengetahuan berbahasa Indonesia. Nama teknologi dan istilah teknis resmi (misalnya Paper, Fabric, HTTPS, SHA-256, dan GitHub Releases) dipertahankan.

## How the assistant works

The bot first searches verified ArenaSystem knowledge sections locally with BM25. When 9Router is configured, the retrieved context and user question are sent to the OpenAI-compatible 9Router chat-completions endpoint to generate a natural Indonesian answer. The system prompt instructs the model not to invent ArenaSystem-specific details that are absent from the knowledge base.

If 9Router is not configured or temporarily unavailable, the bot falls back to the local BM25 answer. The local search remains the factual grounding layer; it is not itself a generative language model.

When 9Router is enabled, questions and the matching documentation context are sent to 9Router and processed according to the selected model/provider's policies. Do not send passwords, API keys, or other secrets. GitHub Releases API is still used for release/download commands.

## Runtime and build

- Java 21 (virtual threads and HttpServer).
- Maven 3.9+ to build.
- Docker multi-stage build is included for Railway.
- Jackson is used only for JSON handling.
- One Railway replica is recommended because short chat context and update de-duplication are held in process memory.

Local build and tests:

    mvn test
    mvn package

Run locally after building:

    export TELEGRAM_BOT_TOKEN="..."
    export TELEGRAM_WEBHOOK_SECRET="use-a-random-secret"
    export PUBLIC_BASE_URL="https://your-public-https-domain"
    java --add-modules jdk.httpserver -jar target/arenasystem-telegram-bot.jar

The runnable shaded JAR is produced at target/arenasystem-telegram-bot.jar.

## Railway deployment

Use the repository au3891427-wq/Arena-System-Plugins-Client-and-Server and set the service Root Directory to /bot/java-bot. Railway detects the Dockerfile in that directory.

Required service variables:

| Variable | Description |
| --- | --- |
| TELEGRAM_BOT_TOKEN | Token from Telegram BotFather |
| TELEGRAM_WEBHOOK_SECRET | Random 1-256 character value using letters, digits, underscore, hyphen |
| ANNOUNCEMENT_API_KEY | Optional secret key (at least 32 characters) for the manual announcement API. Leave unset to disable the endpoint. |
| PUBLIC_BASE_URL | The generated Railway HTTPS domain, without a trailing slash |

Optional variables:

| Variable | Default | Description |
| --- | --- | --- |
| GITHUB_REPOSITORY | au3891427-wq/Arena-System-Plugins-Client-and-Server | Repository used for stable release lookup |
| TELEGRAM_ALLOWED_CHAT_IDS | empty | Comma-separated chat allowlist; empty allows all chats |
| RELEASE_MONITOR_STATE_FILE | `data/release-monitor-state.json` | Persistent JSON state. On Railway, set `/data/release-monitor-state.json` and mount a persistent volume at `/data`. |
| RELEASE_MONITOR_INTERVAL_SECONDS | `60` | Release polling interval in seconds (30–3600). |
| NINEROUTER_BASE_URL | `https://9router.com/v1` | OpenAI-compatible 9Router API base URL. |
| NINEROUTER_API_KEY | empty | Secret 9Router API key. Keep it in Railway Variables, never in Git. |
| NINEROUTER_MODEL | empty | Exact model ID from the 9Router model list. Must be set together with the API key. |
| NINEROUTER_TIMEOUT_SECONDS | `45` | 9Router request timeout (5–120 seconds). |
| PORT | Railway-provided | HTTP listener port |

The service serves GET /healthz, POST /telegram/webhook, and (when `ANNOUNCEMENT_API_KEY` is configured) POST `/api/announcement`. Manual announcement requests use `Authorization: Bearer <ANNOUNCEMENT_API_KEY>`, `Content-Type: application/json`, and a JSON body such as `{"text":"📢 Pengumuman ArenaSystem\\n\\nVersi terbaru sudah tersedia."}`. The endpoint accepts only the message text; it always uses the group/topic configured with `/setreleasechannel`, applies a 3,900-character limit and a rate limit of five requests per minute, and returns only after Telegram confirms the send. Never commit or share the API key. Attach a Railway persistent volume at `/data` and set `RELEASE_MONITOR_STATE_FILE=/data/release-monitor-state.json` before relying on the monitor in production. After deployment, run `/setreleasechannel` as a group administrator inside the forum topic that should receive asset announcements. The bot must be an administrator in that group so it can verify the caller's administrator status. Railway should route the public domain to the container's PORT.

## ChatGPT announcement bridge

The Chrome extension source is in `tools/chatgpt-telegram-announcer/`. It is intended for personal ChatGPT accounts that cannot connect custom MCP write tools: the user asks ChatGPT to draft an announcement, opens the extension, imports the latest assistant reply (or selected text), reviews/edits the preview, and explicitly confirms sending. The API key is stored in Chrome local extension storage and is not inserted into the ChatGPT page. The extension does not monitor conversations or send automatically.

Install by opening `chrome://extensions`, enabling Developer mode, choosing **Load unpacked**, and selecting the extension folder. Then paste the API key once in the extension popup and save it. The bot's `/setreleasechannel` destination must already be configured.

## Knowledge base maintenance

Edit src/main/resources/knowledge/arenasystem.md only with information confirmed for stable releases. Add specific class mechanics, installation instructions, and compatibility data only when maintainers have verified them. The local search engine reloads this resource at process startup, so a redeploy is required after edits.

## Privacy and limits

- BM25 knowledge retrieval runs locally within the Java process. If 9Router is enabled, the user question and retrieved context are sent to 9Router over HTTPS for answer generation.
- If NINEROUTER_API_KEY or NINEROUTER_MODEL is unset, the bot uses local BM25 responses only. If a 9Router request fails, it falls back to the local answer.
- A short previous question/answer is kept in process memory for up to 30 minutes of inactivity to support simple follow-ups; /reset clears it.
- Memory is lost when the service restarts.
- The bot limits each user to 5 assistant requests per minute and the whole service to 40 per minute.
- /latest and /downloads contact GitHub's public Releases API.
- The release asset monitor uses GitHub's Releases API with ETag revalidation, checks at the configured interval, and stores processed asset IDs and the Telegram destination in the persistent state file.

## Tests

GitHub Actions runs Maven tests and packages the Java 21 application whenever bot/java-bot files change. The active Railway service uses this directory as its Docker build context.

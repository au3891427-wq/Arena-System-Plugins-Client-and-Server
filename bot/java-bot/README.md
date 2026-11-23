# ArenaSystem Telegram Bot — Java Edition

A Java 21 Telegram bot with a local, retrieval-based ArenaSystem assistant. It does not call OpenAI, Gemini, or any external LLM API.

## What it does

- /start opens the Telegram menu.
- /latest shows the newest published stable GitHub Release that contains an uploaded JAR.
- /downloads lists JAR assets and provides direct download buttons.
- /ask <question> searches the local ArenaSystem knowledge base using BM25 ranking and deterministic response composition.
- Private chats can send questions directly; in group topics use /ask <question> or reply to a bot message.
- /reset clears the user's short conversation context.
- /privacy describes local processing and in-memory context.
- /setreleasechannel configures the current Telegram forum topic as the destination for later JAR upload announcements (group administrators only).
- GitHub Actions announces stable Release publication. The Java service polls GitHub Releases for JAR assets uploaded after publication and announces each new asset once.

## Bahasa jawaban

Semua jawaban dari fitur /ask disusun dalam Bahasa Indonesia. Pertanyaan berbahasa Inggris tetap dapat dikenali melalui kamus sinonim, tetapi konten jawaban tetap diambil dari basis pengetahuan berbahasa Indonesia. Nama teknologi dan istilah teknis resmi (misalnya Paper, Fabric, HTTPS, SHA-256, dan GitHub Releases) dipertahankan.

## What "local assistant" means

This is a retrieval-based support assistant, not a locally trained large language model and not a generative ChatGPT replacement. It tokenizes Indonesian/English questions, expands a small synonym dictionary, ranks verified knowledge sections with BM25, and composes an answer from the best matching sections.

It is deliberately conservative: when the knowledge base does not support an answer (for example, undocumented class damage values or exact compatibility), it says the information is not documented instead of inventing details.

Telegram updates are processed by the Java service. Questions are not sent to an external AI provider. GitHub Releases API is still used for release/download commands.

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
| PUBLIC_BASE_URL | The generated Railway HTTPS domain, without a trailing slash |

Optional variables:

| Variable | Default | Description |
| --- | --- | --- |
| GITHUB_REPOSITORY | au3891427-wq/Arena-System-Plugins-Client-and-Server | Repository used for stable release lookup |
| TELEGRAM_ALLOWED_CHAT_IDS | empty | Comma-separated chat allowlist; empty allows all chats |
| PORT | Railway-provided | HTTP listener port |

The service serves GET /healthz and POST /telegram/webhook. On startup it registers Telegram commands, sets the webhook automatically, and starts the release asset monitor. Attach a Railway persistent volume at `/data` and set `RELEASE_MONITOR_STATE_FILE=/data/release-monitor-state.json` before relying on the monitor in production. After deployment, run `/setreleasechannel` as a group administrator inside the forum topic that should receive asset announcements. The bot must be an administrator in that group so it can verify the caller's administrator status. Railway should route the public domain to the container's PORT.

## Knowledge base maintenance

Edit src/main/resources/knowledge/arenasystem.md only with information confirmed for stable releases. Add specific class mechanics, installation instructions, and compatibility data only when maintainers have verified them. The local search engine reloads this resource at process startup, so a redeploy is required after edits.

## Privacy and limits

- Search and answer composition run locally within the Java process.
- No user question is sent to an external LLM service.
- A short previous question/answer is kept in process memory for up to 30 minutes of inactivity to support simple follow-ups; /reset clears it.
- Memory is lost when the service restarts.
- The bot limits each user to 5 assistant requests per minute and the whole service to 40 per minute.
- /latest and /downloads contact GitHub's public Releases API.
- The release asset monitor uses GitHub's Releases API with ETag revalidation, checks at the configured interval, and stores processed asset IDs and the Telegram destination in the persistent state file.

## Tests

GitHub Actions runs Maven tests and packages the Java 21 application whenever bot/java-bot files change. The active Railway service uses this directory as its Docker build context.

# ArenaSystem Telegram Assistant

A Node.js Telegram bot for ArenaSystem. It combines styled in-chat menus, stable release browsing, and an AI support assistant.

## Features

- /start opens the ArenaSystem menu with inline buttons.
- /latest shows the newest stable GitHub Release.
- /downloads lists uploaded JAR assets from that stable release.
- /ask <question> asks the ArenaSystem AI assistant.
- Private chats can send a question directly without a command.
- In group topics, use /ask <question> or reply to a bot message. Replies stay in the originating topic.
- /reset clears the current user's short conversation context.
- /privacy explains how short-lived conversation context is handled.
- Release announcements are still sent by the separate GitHub Actions workflow in .github/workflows/telegram-release-notify.yml.

## Runtime

- Node.js 22.9 or newer.
- No runtime npm dependencies; it uses Node's built-in HTTP server and fetch.
- A public HTTPS endpoint is required for Telegram webhooks.
- One running instance is recommended because conversation history and update de-duplication are kept in process memory.

## Environment variables

| Variable | Required | Description |
| --- | --- | --- |
| TELEGRAM_BOT_TOKEN | Yes | Token from Telegram's @BotFather |
| TELEGRAM_WEBHOOK_SECRET | Yes | Random secret used to authenticate Telegram webhook requests |
| PUBLIC_BASE_URL | Yes for live webhook | Public HTTPS base URL of the deployed service, without a path |
| OPENAI_API_KEY | For AI chat | OpenAI API project key |
| OPENAI_MODEL | No | Model ID; defaults to gpt-6-luna |
| GITHUB_REPOSITORY | No | Stable release repository; defaults to au3891427-wq/Arena-System-Plugins-Client-and-Server |
| TELEGRAM_ALLOWED_CHAT_IDS | No | Comma-separated allowlist of chat IDs; empty means no chat allowlist |
| PORT | No | HTTP port; Railway provides this automatically |

Copy .env.example as a local reference. Never commit a real .env file or any token.

## Deploy on Railway

1. Create a Railway project and connect the GitHub repository au3891427-wq/Arena-System-Plugins-Client-and-Server.
2. Set the service Root Directory to /bot/telegram-bot.
3. Use npm start as the start command (Railway can also detect it from package.json).
4. Add TELEGRAM_BOT_TOKEN and TELEGRAM_WEBHOOK_SECRET as service variables. Generate a random webhook secret containing only letters, numbers, _ and -.
5. Add OPENAI_API_KEY to enable AI replies. Set OPENAI_MODEL to gpt-6-luna or another model available to your API project.
6. Generate a public HTTPS domain for the service.
7. Add PUBLIC_BASE_URL using that exact HTTPS domain, for example https://your-service.up.railway.app.
8. Deploy/redeploy. On startup the app registers its Telegram commands and webhook automatically.
9. Check https://your-service-domain/healthz. The response should contain "status":"ok".
10. Send /start to the bot in a private chat. In the group, use /ask <question> inside a topic.

If PUBLIC_BASE_URL is added after the first deployment, redeploy the service so startup can register the webhook.

## Local development

Copy .env.example to .env, fill local values, then run npm test and npm start from this directory. The start command loads .env when the file exists.

For local webhook testing, expose the local port through an HTTPS tunnel and set PUBLIC_BASE_URL to the public tunnel URL. Do not expose the webhook without TELEGRAM_WEBHOOK_SECRET.

## Webhook notes

This service uses Telegram setWebhook with allowed_updates set to message and callback_query. Telegram getUpdates polling cannot be used while this webhook is active. The webhook is authenticated using Telegram's X-Telegram-Bot-Api-Secret-Token header.

## AI behavior and costs

- The assistant uses the curated KNOWLEDGE.md file plus the latest stable release notes as its factual context.
- It is instructed to say when a feature or compatibility detail is not documented instead of guessing.
- Conversation history is kept in process memory for up to 30 minutes of inactivity, with a maximum of 10 messages per chat/user/topic context. /reset clears that context. Restarting the service clears all in-memory context.
- AI requests are limited to 5 per user per minute, 20 total per minute, and 300 total per day in this process. These in-memory limits reset when the service restarts and should be combined with API project spending limits.
- OpenAI API usage is billed separately from a ChatGPT subscription. Set API project usage limits and monitor usage in the OpenAI Platform before sharing the bot publicly.
- Keep API keys in Railway service variables, never in the repository.

## Tests

Run npm test from this directory. The GitHub Actions workflow also runs these tests when bot files change.

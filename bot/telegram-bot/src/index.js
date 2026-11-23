import { createServer } from "node:http";
import { timingSafeEqual } from "node:crypto";

import { readConfig, validateConfig } from "./config.js";
import { askArenaSystemAI } from "./ai.js";
import { getJarAssets, getLatestStableRelease } from "./github.js";
import {
  answerCallbackQuery,
  sendChatAction,
  sendMessage,
  setBotCommands,
  setBotWebhook,
  telegramApi
} from "./telegram.js";
import {
  clipText,
  makeConversationKey,
  parseBotCommand,
  splitTelegramText
} from "./utils.js";

const config = readConfig();
validateConfig(config);

const histories = new Map();
const requestTimes = new Map();
const processedUpdateIds = new Set();
const inFlightUpdateIds = new Set();

const HISTORY_TTL_MS = 30 * 60 * 1000;
const MAX_HISTORY_MESSAGES = 10;
const MAX_QUESTION_LENGTH = 1500;
const AI_REQUESTS_PER_MINUTE = 5;
const WEBHOOK_PATH = "/telegram/webhook";
const RELEASES_URL = "https://github.com/" + config.githubRepository + "/releases";

function secureStringEquals(left, right) {
  if (typeof left !== "string" || typeof right !== "string") {
    return false;
  }

  const leftBuffer = Buffer.from(left);
  const rightBuffer = Buffer.from(right);

  if (leftBuffer.length !== rightBuffer.length) {
    return false;
  }

  return timingSafeEqual(leftBuffer, rightBuffer);
}

function isChatAllowed(chatId) {
  return config.allowedChatIds.size === 0 ||
    config.allowedChatIds.has(String(chatId));
}

function getMainMenuKeyboard() {
  return {
    inline_keyboard: [
      [
        { text: "📦 Latest Release", callback_data: "menu:latest" },
        { text: "⬇️ Downloads", callback_data: "menu:downloads" }
      ],
      [
        { text: "🤖 Ask AI", callback_data: "menu:ask" },
        { text: "📚 Help Center", callback_data: "menu:help" }
      ]
    ]
  };
}

function getHelpKeyboard() {
  return {
    inline_keyboard: [
      [
        { text: "📦 Latest Release", callback_data: "menu:latest" },
        { text: "⬇️ Downloads", callback_data: "menu:downloads" }
      ],
      [
        { text: "🤖 Ask AI", callback_data: "menu:ask" },
        { text: "🏠 Main Menu", callback_data: "menu:home" }
      ]
    ]
  };
}

function threadOptions(message) {
  return message?.message_thread_id == null
    ? {}
    : { threadId: message.message_thread_id };
}

async function reply(chatId, text, options = {}) {
  const parts = splitTelegramText(text);
  let lastResult = null;

  for (let index = 0; index < parts.length; index += 1) {
    lastResult = await sendMessage(
      config.telegramToken,
      chatId,
      parts[index],
      index === 0 ? options : { threadId: options.threadId }
    );
  }

  return lastResult;
}

async function sendWelcome(chatId, options = {}) {
  const message = [
    "🛡️ ARENASYSTEM",
    "OFFICIAL COMMUNITY ASSISTANT",
    "",
    "Welcome! Your companion for ArenaSystem updates, downloads, documentation, and technical support.",
    "",
    "Choose an option below to get started."
  ].join("\n");

  return reply(chatId, message, {
    ...options,
    replyMarkup: getMainMenuKeyboard()
  });
}

async function sendHelp(chatId, options = {}) {
  const message = [
    "📚 ARENASYSTEM HELP CENTER",
    "",
    "/start — Open the main menu",
    "/latest — Latest stable release",
    "/downloads — Browse stable JAR files",
    "/ask <question> — Ask the AI assistant",
    "/reset — Clear your short conversation context",
    "/privacy — How conversation context is handled",
    "",
    "In a private chat, you can also send a question directly.",
    "In a group topic, use /ask followed by your question."
  ].join("\n");

  return reply(chatId, message, {
    ...options,
    replyMarkup: getHelpKeyboard()
  });
}

async function sendLatestRelease(chatId, options = {}, downloadsOnly = false) {
  let release;

  try {
    release = await getLatestStableRelease(config.githubRepository);
  } catch (error) {
    console.error("GitHub release lookup failed:", error.message);
    return reply(
      chatId,
      "I couldn't check the stable releases right now. Please try again later.\n\n" +
        "Release page: " + RELEASES_URL,
      options
    );
  }

  if (!release) {
    return reply(
      chatId,
      "📦 ARENASYSTEM STABLE RELEASES\n\nNo stable release with an uploaded JAR is available yet.\n\n" +
        "Check the release page for future downloads:\n" + RELEASES_URL,
      options
    );
  }

  const assets = getJarAssets(release);
  const title = release.name || release.tag_name || "ArenaSystem";
  const date = release.published_at
    ? new Date(release.published_at).toISOString().slice(0, 10)
    : "Not specified";

  const lines = downloadsOnly
    ? [
        "⬇️ ARENASYSTEM DOWNLOADS",
        "",
        title,
        "Version: " + release.tag_name,
        "",
        "Choose a file below. Use only the artifact that matches your server or client."
      ]
    : [
        "📦 ARENASYSTEM — LATEST STABLE",
        "",
        title,
        "Version: " + release.tag_name,
        "Published: " + date,
        "",
        "Release notes:",
        String(release.body || "No release notes were provided.").slice(0, 1800)
      ];

  if (downloadsOnly) {
    lines.push("");
    lines.push("Available JAR files:");
    for (const asset of assets.slice(0, 8)) {
      lines.push("• " + asset.name);
    }
  }

  const keyboardRows = assets.slice(0, 6).map((asset) => [
    {
      text: "⬇️ " + clipText(asset.name, 42),
      url: asset.browser_download_url
    }
  ]);

  keyboardRows.push([
    { text: "📝 Full Changelog", url: release.html_url },
    { text: "📚 All Releases", url: RELEASES_URL }
  ]);

  return reply(chatId, lines.join("\n"), {
    ...options,
    replyMarkup: { inline_keyboard: keyboardRows }
  });
}

function trimExpiredState() {
  const now = Date.now();

  for (const [key, value] of histories) {
    if (now - value.updatedAt > HISTORY_TTL_MS) {
      histories.delete(key);
    }
  }

  for (const [key, timestamps] of requestTimes) {
    const recent = timestamps.filter((timestamp) => now - timestamp < 60000);
    if (recent.length === 0) {
      requestTimes.delete(key);
    } else {
      requestTimes.set(key, recent);
    }
  }

  while (processedUpdateIds.size > 4096) {
    const oldest = processedUpdateIds.values().next().value;
    processedUpdateIds.delete(oldest);
  }
}

function consumeAiRateLimit(userId) {
  const now = Date.now();
  const recent = (requestTimes.get(String(userId)) || [])
    .filter((timestamp) => now - timestamp < 60000);

  if (recent.length >= AI_REQUESTS_PER_MINUTE) {
    requestTimes.set(String(userId), recent);
    return false;
  }

  recent.push(now);
  requestTimes.set(String(userId), recent);
  return true;
}

async function answerWithAI(message, question) {
  const chatId = message.chat.id;
  const userId = message.from.id;
  const threadId = message.message_thread_id ?? null;

  if (!config.openAiApiKey) {
    return reply(
      chatId,
      "🤖 AI support is not configured yet. Please contact an ArenaSystem administrator.",
      { threadId }
    );
  }

  if (!question || question.length > MAX_QUESTION_LENGTH) {
    return reply(
      chatId,
      "Please send a question between 1 and " + MAX_QUESTION_LENGTH + " characters.",
      { threadId }
    );
  }

  if (!consumeAiRateLimit(userId)) {
    return reply(
      chatId,
      "You've reached the AI request limit. Please wait a minute before asking again.",
      { threadId }
    );
  }

  await sendChatAction(config.telegramToken, chatId, "typing", threadId);

  const key = makeConversationKey(chatId, userId, threadId);
  const stored = histories.get(key);
  const history = stored ? stored.messages.slice() : [];

  let release = null;
  try {
    release = await getLatestStableRelease(config.githubRepository);
  } catch (error) {
    console.error("Could not attach latest release context:", error.message);
  }

  try {
    const answer = await askArenaSystemAI({
      apiKey: config.openAiApiKey,
      model: config.openAiModel,
      repository: config.githubRepository,
      release,
      history,
      question
    });

    const nextHistory = [
      ...history,
      { role: "user", content: question },
      { role: "assistant", content: answer }
    ].slice(-MAX_HISTORY_MESSAGES);

    histories.set(key, {
      messages: nextHistory,
      updatedAt: Date.now()
    });

    return reply(chatId, answer, { threadId });
  } catch (error) {
    console.error("AI response failed:", error.message);

    if (error.message === "AI_NOT_CONFIGURED") {
      return reply(
        chatId,
        "AI support is not configured yet. Please contact an ArenaSystem administrator.",
        { threadId }
      );
    }

    return reply(
      chatId,
      "Sorry, I couldn't generate an answer right now. Please try again shortly.",
      { threadId }
    );
  }
}

async function handleCommand(message, command, argument) {
  const chatId = message.chat.id;
  const options = threadOptions(message);

  switch (command) {
    case "start":
    case "menu":
      if (argument.toLowerCase() === "ask") {
        return reply(
          chatId,
          "🤖 AI Assistant is ready. Send your question in this private chat.",
          options
        );
      }
      return sendWelcome(chatId, options);

    case "help":
      return sendHelp(chatId, options);

    case "latest":
    case "status":
      return sendLatestRelease(chatId, options, false);

    case "download":
    case "downloads":
      return sendLatestRelease(chatId, options, true);

    case "ask":
      if (!argument) {
        return reply(
          chatId,
          "🤖 Send your question like this:\n/ask How do I install the ArenaSystem client mod?",
          options
        );
      }
      return answerWithAI(message, argument);

    case "reset": {
      const key = makeConversationKey(
        chatId,
        message.from.id,
        message.message_thread_id ?? null
      );
      histories.delete(key);
      return reply(
        chatId,
        "🧹 Your short ArenaSystem AI conversation context has been cleared.",
        options
      );
    }

    case "privacy":
      return reply(
        chatId,
        [
          "🔒 PRIVACY",
          "",
          "The bot keeps a short conversation context in its running process memory so follow-up questions make sense.",
          "That context expires after 30 minutes of inactivity, can be cleared with /reset, and is lost when the bot restarts.",
          "AI questions and their short context are sent to the configured AI API to generate answers.",
          "",
          "Do not send passwords, bot tokens, API keys, or other private secrets."
        ].join("\n"),
        options
      );

    default:
      return sendHelp(chatId, options);
  }
}

async function handleMessage(message) {
  if (!message?.chat?.id || !message.from || message.from.is_bot) {
    return;
  }

  const chatId = message.chat.id;
  if (!isChatAllowed(chatId)) {
    return;
  }

  const text = typeof message.text === "string" ? message.text.trim() : "";
  if (!text) {
    return;
  }

  const parsed = parseBotCommand(text);
  if (parsed) {
    return handleCommand(message, parsed.command, parsed.argument);
  }

  if (message.chat.type === "private") {
    return answerWithAI(message, text);
  }

  const isReplyToBot = message.reply_to_message?.from?.is_bot === true;
  if (isReplyToBot) {
    return answerWithAI(message, text);
  }
}

async function handleCallbackQuery(callbackQuery) {
  if (!callbackQuery?.id) {
    return;
  }

  const message = callbackQuery.message;
  const chatId = message?.chat?.id;
  const userId = callbackQuery.from?.id;

  await answerCallbackQuery(
    config.telegramToken,
    callbackQuery.id
  ).catch((error) => {
    console.error("Could not acknowledge callback query:", error.message);
  });

  if (!chatId || !userId || !isChatAllowed(chatId)) {
    return;
  }

  const options = threadOptions(message);
  const data = String(callbackQuery.data || "");

  switch (data) {
    case "menu:home":
      return sendWelcome(chatId, options);

    case "menu:latest":
      return sendLatestRelease(chatId, options, false);

    case "menu:downloads":
      return sendLatestRelease(chatId, options, true);

    case "menu:help":
      return sendHelp(chatId, options);

    case "menu:ask":
      if (message.chat.type === "private") {
        return reply(
          chatId,
          "🤖 Send your question in this chat. You can ask in Indonesian or English.",
          options
        );
      }

      return reply(
        chatId,
        "🤖 To ask in this topic, send /ask followed by your question.",
        options
      );

    default:
      return reply(chatId, "That menu action is not available. Use /start to open the main menu.", options);
  }
}

async function processUpdate(update) {
  if (update.callback_query) {
    return handleCallbackQuery(update.callback_query);
  }

  if (update.message) {
    return handleMessage(update.message);
  }
}

async function readRequestJson(request, maxBytes = 1024 * 1024) {
  const chunks = [];
  let totalBytes = 0;

  for await (const chunk of request) {
    totalBytes += chunk.length;
    if (totalBytes > maxBytes) {
      throw new Error("Request body is too large.");
    }
    chunks.push(chunk);
  }

  const body = Buffer.concat(chunks).toString("utf8");
  return JSON.parse(body);
}

const server = createServer(async (request, response) => {
  const requestUrl = new URL(request.url || "/", "http://localhost");

  if (request.method === "GET" && requestUrl.pathname === "/healthz") {
    response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    response.end(JSON.stringify({
      status: "ok",
      service: "arenasystem-telegram-bot",
      aiConfigured: Boolean(config.openAiApiKey)
    }));
    return;
  }

  if (request.method !== "POST" || requestUrl.pathname !== WEBHOOK_PATH) {
    response.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    response.end("Not found");
    return;
  }

  const suppliedSecret = request.headers["x-telegram-bot-api-secret-token"];
  if (!secureStringEquals(
    Array.isArray(suppliedSecret) ? suppliedSecret[0] : suppliedSecret,
    config.webhookSecret
  )) {
    response.writeHead(401, { "content-type": "text/plain; charset=utf-8" });
    response.end("Unauthorized");
    return;
  }

  let update;
  try {
    update = await readRequestJson(request);
  } catch (error) {
    response.writeHead(400, { "content-type": "text/plain; charset=utf-8" });
    response.end(error.message === "Request body is too large." ? "Payload too large" : "Invalid JSON");
    return;
  }

  const updateId = update?.update_id;
  if (!Number.isSafeInteger(updateId)) {
    response.writeHead(400, { "content-type": "text/plain; charset=utf-8" });
    response.end("Invalid Telegram update");
    return;
  }

  if (processedUpdateIds.has(updateId) || inFlightUpdateIds.has(updateId)) {
    response.writeHead(200);
    response.end("OK");
    return;
  }

  inFlightUpdateIds.add(updateId);
  try {
    await processUpdate(update);
    processedUpdateIds.add(updateId);
    trimExpiredState();
    response.writeHead(200);
    response.end("OK");
  } catch (error) {
    console.error("Telegram update processing failed:", error.message);
    response.writeHead(500, { "content-type": "text/plain; charset=utf-8" });
    response.end("Processing failed");
  } finally {
    inFlightUpdateIds.delete(updateId);
  }
});

server.listen(config.port, "0.0.0.0", async () => {
  console.log("ArenaSystem Telegram bot listening on port " + config.port);

  try {
    await setBotCommands(config.telegramToken);
    console.log("Telegram bot commands registered.");
  } catch (error) {
    console.error("Could not register Telegram commands:", error.message);
  }

  if (config.publicBaseUrl) {
    try {
      const webhookUrl = config.publicBaseUrl + WEBHOOK_PATH;
      await setBotWebhook(
        config.telegramToken,
        webhookUrl,
        config.webhookSecret
      );
      console.log("Telegram webhook registered.");
    } catch (error) {
      console.error("Could not register Telegram webhook:", error.message);
    }
  } else {
    console.warn("PUBLIC_BASE_URL is empty; Telegram webhook was not registered.");
  }
});

process.on("SIGTERM", () => {
  server.close(() => process.exit(0));
});

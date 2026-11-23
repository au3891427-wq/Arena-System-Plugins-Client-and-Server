const TELEGRAM_API_BASE = "https://api.telegram.org/bot";

export async function telegramApi(token, method, payload = {}, timeoutMs = 15000) {
  const response = await fetch(TELEGRAM_API_BASE + token + "/" + method, {
    method: "POST",
    headers: {
      "content-type": "application/json"
    },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(timeoutMs)
  });

  let data;
  try {
    data = await response.json();
  } catch {
    throw new Error("Telegram API returned an invalid JSON response.");
  }

  if (!response.ok || !data?.ok) {
    const reason = data?.description || ("HTTP " + response.status);
    throw new Error("Telegram " + method + " failed: " + reason);
  }

  return data.result;
}

export async function sendMessage(token, chatId, text, options = {}) {
  const payload = {
    chat_id: String(chatId),
    text,
    disable_web_page_preview: true
  };

  if (options.threadId != null) {
    payload.message_thread_id = Number(options.threadId);
  }

  if (options.replyMarkup) {
    payload.reply_markup = options.replyMarkup;
  }

  if (options.replyToMessageId != null) {
    payload.reply_parameters = {
      message_id: Number(options.replyToMessageId),
      allow_sending_without_reply: true
    };
  }

  return telegramApi(token, "sendMessage", payload, options.timeoutMs || 20000);
}

export async function sendChatAction(token, chatId, action = "typing", threadId = null) {
  const payload = {
    chat_id: String(chatId),
    action
  };

  if (threadId != null) {
    payload.message_thread_id = Number(threadId);
  }

  return telegramApi(token, "sendChatAction", payload);
}

export async function answerCallbackQuery(token, callbackQueryId, text = "") {
  const payload = {
    callback_query_id: callbackQueryId,
    cache_time: 0
  };

  if (text) {
    payload.text = text;
  }

  return telegramApi(token, "answerCallbackQuery", payload);
}

export async function setBotCommands(token) {
  return telegramApi(token, "setMyCommands", {
    commands: [
      { command: "start", description: "Open the ArenaSystem assistant" },
      { command: "help", description: "Show commands and help" },
      { command: "latest", description: "Show the latest stable release" },
      { command: "downloads", description: "Browse stable JAR downloads" },
      { command: "ask", description: "Ask the ArenaSystem AI assistant" },
      { command: "reset", description: "Clear your short chat context" },
      { command: "privacy", description: "Read how chat context is handled" }
    ]
  });
}

export async function setBotWebhook(token, webhookUrl, secret) {
  return telegramApi(token, "setWebhook", {
    url: webhookUrl,
    secret_token: secret,
    allowed_updates: ["message", "callback_query"],
    max_connections: 20
  });
}

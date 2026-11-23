export function readConfig(env = process.env) {
  const port = Number.parseInt(env.PORT || "3000", 10);
  const allowedChatIds = (env.TELEGRAM_ALLOWED_CHAT_IDS || "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);

  return {
    port,
    telegramToken: (env.TELEGRAM_BOT_TOKEN || "").trim(),
    webhookSecret: (env.TELEGRAM_WEBHOOK_SECRET || "").trim(),
    publicBaseUrl: (env.PUBLIC_BASE_URL || "").trim().replace(/\/+$/, ""),
    openAiApiKey: (env.OPENAI_API_KEY || "").trim(),
    openAiModel: (env.OPENAI_MODEL || "gpt-6-luna").trim(),
    githubRepository: (env.GITHUB_REPOSITORY ||
      "au3891427-wq/Arena-System-Plugins-Client-and-Server").trim(),
    allowedChatIds: new Set(allowedChatIds)
  };
}

export function validateConfig(config) {
  if (!Number.isInteger(config.port) || config.port < 1 || config.port > 65535) {
    throw new Error("PORT must be a valid TCP port.");
  }

  if (!config.telegramToken) {
    throw new Error("TELEGRAM_BOT_TOKEN is required.");
  }

  if (!config.webhookSecret ||
      config.webhookSecret.length > 256 ||
      !/^[A-Za-z0-9_-]+$/.test(config.webhookSecret)) {
    throw new Error(
      "TELEGRAM_WEBHOOK_SECRET must contain 1-256 characters: letters, numbers, underscores, or hyphens."
    );
  }

  if (config.publicBaseUrl) {
    let url;
    try {
      url = new URL(config.publicBaseUrl);
    } catch {
      throw new Error("PUBLIC_BASE_URL must be a valid HTTPS URL.");
    }

    if (url.protocol !== "https:") {
      throw new Error("PUBLIC_BASE_URL must use HTTPS.");
    }
  }

  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(config.githubRepository)) {
    throw new Error("GITHUB_REPOSITORY must use owner/repository format.");
  }
}

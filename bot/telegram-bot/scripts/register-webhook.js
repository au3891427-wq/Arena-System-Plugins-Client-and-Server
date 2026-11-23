import { readConfig, validateConfig } from "../src/config.js";
import { setBotCommands, setBotWebhook } from "../src/telegram.js";

const config = readConfig();
validateConfig(config);

if (!config.publicBaseUrl) {
  throw new Error("Set PUBLIC_BASE_URL before registering the Telegram webhook.");
}

const webhookUrl = config.publicBaseUrl + "/telegram/webhook";

await setBotCommands(config.telegramToken);
await setBotWebhook(
  config.telegramToken,
  webhookUrl,
  config.webhookSecret
);

console.log("Telegram webhook registered at " + webhookUrl);

export function parseBotCommand(text) {
  if (typeof text !== "string") {
    return null;
  }

  const match = /^\/([a-z0-9_]+)(?:@[a-z0-9_]+)?(?:\s+([\s\S]*))?$/i.exec(text.trim());
  if (!match) {
    return null;
  }

  return {
    command: match[1].toLowerCase(),
    argument: (match[2] || "").trim()
  };
}

export function splitTelegramText(text, maxLength = 3900) {
  if (!Number.isInteger(maxLength) || maxLength < 100 || maxLength > 4096) {
    throw new RangeError("maxLength must be between 100 and 4096.");
  }

  const input = String(text ?? "");
  if (input.length <= maxLength) {
    return [input];
  }

  const chunks = [];
  let remaining = input;

  while (remaining.length > maxLength) {
    let splitAt = remaining.lastIndexOf("\n", maxLength);

    if (splitAt < Math.floor(maxLength * 0.55)) {
      splitAt = maxLength;
    }

    chunks.push(remaining.slice(0, splitAt).trimEnd());
    remaining = remaining.slice(splitAt).trimStart();
  }

  if (remaining.length > 0) {
    chunks.push(remaining);
  }

  return chunks;
}

export function makeConversationKey(chatId, userId, threadId = null) {
  return [String(chatId), String(userId), threadId == null ? "main" : String(threadId)].join(":");
}

export function clipText(value, maxLength = 48) {
  const text = String(value ?? "");
  if (text.length <= maxLength) {
    return text;
  }

  return text.slice(0, Math.max(1, maxLength - 1)).trimEnd() + "…";
}

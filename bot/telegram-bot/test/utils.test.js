import test from "node:test";
import assert from "node:assert/strict";

import {
  makeConversationKey,
  parseBotCommand,
  splitTelegramText
} from "../src/utils.js";

test("parses commands with bot usernames and arguments", () => {
  assert.deepEqual(parseBotCommand("/ask@ArenaSystemUpdatesBot how do I install it?"), {
    command: "ask",
    argument: "how do I install it?"
  });
});

test("parses commands without arguments", () => {
  assert.deepEqual(parseBotCommand("/latest"), {
    command: "latest",
    argument: ""
  });
});

test("does not treat regular text as a command", () => {
  assert.equal(parseBotCommand("hello ArenaSystem"), null);
});

test("splits long Telegram messages without exceeding the configured limit", () => {
  const input = "first paragraph\n\n" + "x".repeat(250);
  const chunks = splitTelegramText(input, 100);

  assert.ok(chunks.length > 1);
  assert.ok(chunks.every((chunk) => chunk.length <= 100));
  assert.equal(chunks.join("").replace(/\s+/g, ""), input.replace(/\s+/g, ""));
});

test("isolates conversation state by chat, user, and forum topic", () => {
  assert.equal(makeConversationKey(-1001, 42, 123), "-1001:42:123");
  assert.notEqual(makeConversationKey(-1001, 42, 123), makeConversationKey(-1001, 42, 456));
});

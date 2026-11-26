const test = require("node:test");
const assert = require("node:assert/strict");
const { ArenaAnnouncementDomExtractor } = require("./announcement-dom-extractor.js");

class FakeElement {
  constructor(tagName, attributes = {}, text = "") {
    this.tagName = tagName.toUpperCase();
    this.attributes = { ...attributes };
    this.className = attributes.class || "";
    this._text = text;
    this.children = [];
    this.parentElement = null;
    this.nodeType = 1;
  }

  append(...children) {
    for (const child of children) {
      child.parentElement = this;
      this.children.push(child);
    }
    return this;
  }

  getAttribute(name) {
    return this.attributes[name] ?? null;
  }

  get innerText() {
    return this._text || this.children.map((child) => child.innerText).join("");
  }

  get textContent() {
    return this.innerText;
  }

  getClientRects() {
    return this.attributes.hidden === "true" ? [] : [{}];
  }

  matches(selector) {
    return selector.split(",").some((part) => this.matchesOne(part.trim()));
  }

  matchesOne(selector) {
    if (selector === "*") return true;
    if (selector === "pre") return this.tagName === "PRE";
    if (selector === "code") return this.tagName === "CODE";
    if (selector === 'article[data-turn="assistant"]') {
      return this.tagName === "ARTICLE" && this.attributes["data-turn"] === "assistant";
    }
    if (selector === ".agent-turn") return String(this.className).split(/\s+/).includes("agent-turn");

    const attrMatch = selector.match(/^\[([\w-]+)(\^=|=)?(?:"([^"]*)")?\]$/);
    if (attrMatch) {
      const [, name, operator, expected] = attrMatch;
      const actual = this.attributes[name];
      if (actual == null) return false;
      if (!operator) return true;
      return operator === "^=" ? actual.startsWith(expected) : actual === expected;
    }

    return false;
  }

  querySelectorAll(selector) {
    const found = [];
    const visit = (node) => {
      for (const child of node.children) {
        if (child.matches(selector)) found.push(child);
        visit(child);
      }
    };
    visit(this);
    return found;
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }

  closest(selector) {
    for (let current = this; current; current = current.parentElement) {
      if (current.matches(selector)) return current;
    }
    return null;
  }
}

function makeDocument(body) {
  return {
    body,
    defaultView: {
      getComputedStyle(element) {
        return {
          display: element.attributes.hidden === "true" ? "none" : "block",
          visibility: "visible"
        };
      }
    },
    querySelectorAll(selector) {
      const result = [];
      if (body.matches(selector)) result.push(body);
      return result.concat(body.querySelectorAll(selector));
    }
  };
}

function makeAssistantTurn({ id, message, withCode = true }) {
  const turn = new FakeElement("div", { "data-testid": `conversation-turn-${id}` });
  const role = new FakeElement("div", { "data-message-author-role": "assistant" });
  turn.append(role);

  if (withCode) {
    const markdown = new FakeElement("div", { "data-markdown-text-style": "assistant-message" });
    const pre = new FakeElement("pre");
    const code = new FakeElement("code", { class: "hljs language-json" }, message);
    pre.append(code);
    markdown.append(pre);
    turn.append(markdown);
  }

  return turn;
}

test("menemukan code block ketika wrapper role dan konten Markdown adalah sibling dalam turn", () => {
  const expected = '{"type":"telegram_announcement","version":1,"message":"uji"}';
  const body = new FakeElement("body").append(makeAssistantTurn({ id: 1, message: expected }));
  const result = ArenaAnnouncementDomExtractor.extractLatestAssistantCodeBlocks(makeDocument(body));

  assert.equal(result.codeBlocks.length, 1);
  assert.equal(result.codeBlocks[0].language, "json");
  assert.equal(result.codeBlocks[0].text, expected);
});

test("tidak mengambil code block dari jawaban assistant sebelumnya jika jawaban terbaru tidak punya code", () => {
  const body = new FakeElement("body").append(
    makeAssistantTurn({ id: 1, message: '{"old":true}' }),
    makeAssistantTurn({ id: 2, message: "", withCode: false })
  );
  const result = ArenaAnnouncementDomExtractor.extractLatestAssistantCodeBlocks(makeDocument(body));

  assert.deepEqual(result.codeBlocks, []);
  assert.match(result.diagnostic, /tidak ditemukan elemen <pre>/);
});

test("mengabaikan code block JSON dari pesan user", () => {
  const user = new FakeElement("div", { "data-message-author-role": "user" });
  const pre = new FakeElement("pre");
  pre.append(new FakeElement("code", { class: "language-json" }, '{"message":"bukan assistant"}'));
  user.append(pre);
  const body = new FakeElement("body").append(user);
  const result = ArenaAnnouncementDomExtractor.extractLatestAssistantCodeBlocks(makeDocument(body));

  assert.deepEqual(result.codeBlocks, []);
  assert.match(result.diagnostic, /Pesan assistant tidak ditemukan/);
});

test("menemukan Markdown pada parent dari H4 conversation-role assistant", () => {
  const expected = '{"type":"telegram_announcement","version":1,"message":"uji parent H4"}';
  const messageWrapper = new FakeElement("div");
  const accessibilityHeading = new FakeElement("h4", {
    class: "sr-only",
    "data-conversation-role": "assistant"
  });
  const markdown = new FakeElement("div", {
    "data-markdown-text-style": "assistant-message"
  });
  const pre = new FakeElement("pre");
  pre.append(new FakeElement("code", { class: "language-json" }, expected));
  markdown.append(pre);
  messageWrapper.append(accessibilityHeading, markdown);

  const result = ArenaAnnouncementDomExtractor.extractLatestAssistantCodeBlocks(
    makeDocument(new FakeElement("body").append(messageWrapper))
  );

  assert.equal(result.codeBlocks.length, 1);
  assert.equal(result.codeBlocks[0].language, "json");
  assert.equal(result.codeBlocks[0].text, expected);
});

test("menemukan konten sibling di article bertanda assistant", () => {
  const expected = '{"type":"telegram_announcement","version":1,"message":"uji article"}';
  const article = new FakeElement("article", { "data-turn": "assistant" });
  const role = new FakeElement("div", { "data-message-author-role": "assistant" });
  const markdown = new FakeElement("div", { "data-markdown-text-style": "assistant-message" });
  const pre = new FakeElement("pre");
  pre.append(new FakeElement("code", { class: "hljs language-json" }, expected));
  article.append(role, markdown.append(pre));
  const result = ArenaAnnouncementDomExtractor.extractLatestAssistantCodeBlocks(
    makeDocument(new FakeElement("body").append(article))
  );

  assert.equal(result.codeBlocks.length, 1);
  assert.equal(result.codeBlocks[0].language, "json");
  assert.equal(result.codeBlocks[0].text, expected);
});

test("menerima pre tanpa elemen code dan membaca bahasa dari atribut", () => {
  const turn = new FakeElement("div", { "data-testid": "conversation-turn-3" });
  const role = new FakeElement("div", { "data-message-author-role": "assistant" });
  const pre = new FakeElement("pre", { "data-language": "JSON" }, '{"message":"uji"}');
  turn.append(role, pre);
  const result = ArenaAnnouncementDomExtractor.extractLatestAssistantCodeBlocks(
    makeDocument(new FakeElement("body").append(turn))
  );

  assert.equal(result.codeBlocks.length, 1);
  assert.equal(result.codeBlocks[0].language, "json");
  assert.equal(result.codeBlocks[0].text, '{"message":"uji"}');
});

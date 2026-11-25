(function exposeDomExtractor(root) {
  "use strict";

  function extractLatestAssistantCodeBlocks(documentRef = document) {
    const markdownSelector = '[data-markdown-text-style="assistant-message"]';
    const assistantSelectors = [
      '[data-message-author-role="assistant"]',
      '[data-conversation-role="assistant"]',
      '[data-role="assistant"]',
      '[data-message-author="assistant"]',
      'article[data-turn="assistant"]',
      '[data-turn="assistant"]',
      ".agent-turn"
    ];

    const getStyle = (element) => {
      try {
        return documentRef.defaultView?.getComputedStyle(element) || null;
      } catch {
        return null;
      }
    };

    const isVisible = (element) => {
      if (!element || element.nodeType !== 1) return false;

      for (let current = element; current?.nodeType === 1; current = current.parentElement) {
        const style = getStyle(current);
        if (style?.display === "none" || style?.visibility === "hidden") return false;
      }

      if (element.getClientRects?.().length > 0) return true;
      return Array.from(element.querySelectorAll?.("*") || []).some(
        (child) => child.getClientRects?.().length > 0
      );
    };

    const latestVisible = (selector) =>
      Array.from(documentRef.querySelectorAll(selector)).filter(isVisible).at(-1) || null;

    let assistantElement = null;
    let matchedSelector = "";

    for (const selector of assistantSelectors) {
      const candidate = latestVisible(selector);
      if (candidate) {
        assistantElement = candidate;
        matchedSelector = selector;
        break;
      }
    }

    // A role marker can sit on a wrapper that does not contain the rendered
    // Markdown node. Resolve the containing conversation turn before searching.
    let searchRoot = null;
    if (assistantElement) {
      searchRoot = assistantElement.closest(
        '[data-testid^="conversation-turn"], [data-turn-key]'
      ) || assistantElement;
    } else {
      // Some ChatGPT layouts expose an explicit assistant-only Markdown node
      // without an ancestor carrying one of the role attributes above.
      const markdownElement = latestVisible(markdownSelector);
      if (markdownElement) {
        assistantElement = markdownElement;
        matchedSelector = markdownSelector;
        searchRoot = markdownElement.closest(
          '[data-testid^="conversation-turn"], [data-turn-key]'
        ) || markdownElement;
      }
    }

    if (!searchRoot) {
      const pageCodeBlockCount = documentRef.querySelectorAll("pre").length;
      return {
        codeBlocks: [],
        source: "not-found",
        diagnostic:
          "Pesan assistant tidak ditemukan. Elemen code block di halaman: " + pageCodeBlockCount + "."
      };
    }

    const markdownRoots = [];
    if (searchRoot.matches?.(markdownSelector) && isVisible(searchRoot)) {
      markdownRoots.push(searchRoot);
    }
    for (const element of searchRoot.querySelectorAll(markdownSelector)) {
      if (isVisible(element)) markdownRoots.push(element);
    }

    // Prefer assistant-marked Markdown descendants. If the DOM omits that
    // marker, use only the selected assistant element/turn, never the whole page.
    const contentRoots = markdownRoots.length > 0
      ? markdownRoots
      : [searchRoot];

    const preElements = new Set();
    for (const contentRoot of contentRoots) {
      if (contentRoot.tagName === "PRE" && isVisible(contentRoot)) {
        preElements.add(contentRoot);
      }
      for (const pre of contentRoot.querySelectorAll("pre")) {
        if (isVisible(pre)) preElements.add(pre);
      }
    }

    const normalizeLanguage = (value) =>
      String(value || "")
        .trim()
        .toLowerCase()
        .replace(/^(?:language-|lang-)/, "");

    const readLanguage = (pre, code) => {
      for (let current = code || pre; current; current = current.parentElement) {
        const explicit =
          current.getAttribute?.("data-language") ||
          current.getAttribute?.("data-code-language") ||
          current.getAttribute?.("data-lang");
        if (explicit) return normalizeLanguage(explicit);

        const classMatch = String(current.className || "").match(
          /(?:^|\s)(?:language-|lang-)([a-z0-9_+-]+)/i
        );
        if (classMatch) return classMatch[1].toLowerCase();
        if (current === searchRoot) break;
      }
      return "";
    };

    const codeBlocks = Array.from(preElements).map((pre) => {
      const code = pre.querySelector("code") || pre;
      return {
        language: readLanguage(pre, code),
        text: String(code.innerText ?? code.textContent ?? "")
      };
    });

    return {
      codeBlocks,
      source: matchedSelector,
      diagnostic: codeBlocks.length === 0
        ? "Container assistant terbaru terdeteksi melalui " + matchedSelector +
          ", tetapi tidak ditemukan elemen <pre> di dalam pesan tersebut."
        : null
    };
  }

  root.ArenaAnnouncementDomExtractor = Object.freeze({
    extractLatestAssistantCodeBlocks
  });
})(typeof globalThis !== "undefined" ? globalThis : window);

if (typeof module !== "undefined" && module.exports) {
  module.exports = { ArenaAnnouncementDomExtractor: globalThis.ArenaAnnouncementDomExtractor };
}

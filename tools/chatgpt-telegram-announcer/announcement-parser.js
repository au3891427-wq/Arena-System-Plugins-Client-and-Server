(function exposeAnnouncementParser(root) {
  "use strict";

  const MAX_MESSAGE_LENGTH = 3900;

  function normalizeLanguage(language) {
    return String(language || "")
      .trim()
      .toLowerCase()
      .replace(/^(?:language-|lang-)/, "");
  }

  function parseJson(text) {
    try {
      return JSON.parse(String(text ?? ""));
    } catch {
      return null;
    }
  }

  function isAnnouncementPayload(payload) {
    return Boolean(
      payload &&
      typeof payload === "object" &&
      !Array.isArray(payload) &&
      payload.type === "telegram_announcement" &&
      payload.version === 1 &&
      typeof payload.message === "string"
    );
  }

  function parseTelegramAnnouncement(codeBlocks) {
    if (!Array.isArray(codeBlocks)) {
      throw new TypeError("Daftar blok kode tidak valid.");
    }

    const explicitJsonBlocks = codeBlocks.filter((block) =>
      block && normalizeLanguage(block.language) === "json"
    );
    const unlabelledAnnouncementBlocks = codeBlocks.filter((block) => {
      if (!block || normalizeLanguage(block.language) !== "") return false;
      return isAnnouncementPayload(parseJson(block.text));
    });

    // ChatGPT's current code viewer can omit the fence language from its DOM.
    // In that case, accept an unlabelled block only when its parsed JSON has
    // the exact announcement discriminator and supported version.
    const candidates = [...explicitJsonBlocks, ...unlabelledAnnouncementBlocks];

    if (candidates.length === 0) {
      throw new Error("Tidak ditemukan blok kode JSON pengumuman. Pastikan blok memakai label json atau skema telegram_announcement v1.");
    }
    if (candidates.length > 1) {
      throw new Error("Ditemukan lebih dari satu blok JSON. Sisakan satu blok pengumuman saja.");
    }

    let payload;
    try {
      payload = JSON.parse(String(candidates[0].text ?? ""));
    } catch {
      throw new Error("Isi blok JSON tidak valid. Periksa sintaks JSON pengumuman.");
    }

    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw new Error("JSON pengumuman harus berupa object.");
    }
    if (payload.type !== "telegram_announcement") {
      throw new Error("Properti 'type' harus bernilai 'telegram_announcement'.");
    }
    if (payload.version !== 1) {
      throw new Error("Versi format pengumuman tidak didukung. Versi yang didukung: 1.");
    }
    if (typeof payload.message !== "string" || payload.message.trim().length === 0) {
      throw new Error("Properti 'message' harus berupa string yang tidak kosong.");
    }

    const message = payload.message.trim();
    if (message.length > MAX_MESSAGE_LENGTH) {
      throw new Error("Pesan terlalu panjang. Maksimal 3.900 karakter.");
    }
    if (message.includes("\u0000")) {
      throw new Error("Pesan mengandung karakter yang tidak valid.");
    }

    return { message };
  }

  root.ArenaAnnouncementParser = Object.freeze({
    parseTelegramAnnouncement,
    MAX_MESSAGE_LENGTH
  });
})(typeof globalThis !== "undefined" ? globalThis : window);

if (typeof module !== "undefined" && module.exports) {
  module.exports = { ArenaAnnouncementParser: globalThis.ArenaAnnouncementParser };
}

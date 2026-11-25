(function exposeAnnouncementParser(root) {
  "use strict";

  const MAX_MESSAGE_LENGTH = 3900;

  function normalizeLanguage(language) {
    return String(language || "")
      .trim()
      .toLowerCase()
      .replace(/^(?:language-|lang-)/, "");
  }

  function parseTelegramAnnouncement(codeBlocks) {
    if (!Array.isArray(codeBlocks)) {
      throw new TypeError("Daftar blok kode tidak valid.");
    }

    const jsonBlocks = codeBlocks.filter((block) =>
      block && normalizeLanguage(block.language) === "json"
    );

    if (jsonBlocks.length === 0) {
      throw new Error("Tidak ditemukan blok kode dengan bahasa JSON.");
    }
    if (jsonBlocks.length > 1) {
      throw new Error("Ditemukan lebih dari satu blok JSON. Sisakan satu blok pengumuman saja.");
    }

    let payload;
    try {
      payload = JSON.parse(String(jsonBlocks[0].text ?? ""));
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

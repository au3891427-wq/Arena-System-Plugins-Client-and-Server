const test = require("node:test");
const assert = require("node:assert/strict");

const { ArenaAnnouncementParser } = require("./announcement-parser.js");

const validPayload = {
  type: "telegram_announcement",
  version: 1,
  message: "📢 ArenaSystem Client Update!\n- Fixed synchronization."
};

function jsonBlock(payload = validPayload) {
  return { language: "json", text: JSON.stringify(payload) };
}

test("mengambil hanya properti message dari satu blok JSON", () => {
  const result = ArenaAnnouncementParser.parseTelegramAnnouncement([
    { language: "text", text: "Penjelasan panjang yang tidak boleh ikut terkirim." },
    jsonBlock()
  ]);
  assert.deepEqual(result, { message: validPayload.message });
});

test("mendukung label bahasa language-json", () => {
  const result = ArenaAnnouncementParser.parseTelegramAnnouncement([
    { language: "language-json", text: JSON.stringify(validPayload) }
  ]);
  assert.equal(result.message, validPayload.message);
});

test("menerima blok tanpa metadata bahasa hanya jika skema announcement v1 cocok", () => {
  const result = ArenaAnnouncementParser.parseTelegramAnnouncement([
    { language: "", text: JSON.stringify(validPayload) }
  ]);
  assert.equal(result.message, validPayload.message);
});

test("menolak JSON biasa tanpa metadata bahasa", () => {
  assert.throws(
    () => ArenaAnnouncementParser.parseTelegramAnnouncement([
      { language: "", text: '{"hello":"world"}' }
    ]),
    /Tidak ditemukan blok kode JSON pengumuman/
  );
});

test("tetap menolak blok dengan bahasa eksplisit selain JSON", () => {
  assert.throws(
    () => ArenaAnnouncementParser.parseTelegramAnnouncement([
      { language: "javascript", text: JSON.stringify(validPayload) }
    ]),
    /Tidak ditemukan blok kode JSON pengumuman/
  );
});

test("menolak jika tidak ada blok JSON", () => {
  assert.throws(
    () => ArenaAnnouncementParser.parseTelegramAnnouncement([
      { language: "javascript", text: "console.log('not an announcement')" }
    ]),
    /Tidak ditemukan blok kode JSON pengumuman/
  );
});

test("menolak lebih dari satu blok JSON/pengumuman", () => {
  assert.throws(
    () => ArenaAnnouncementParser.parseTelegramAnnouncement([
      jsonBlock(),
      jsonBlock()
    ]),
    /lebih dari satu blok JSON/
  );
  assert.throws(
    () => ArenaAnnouncementParser.parseTelegramAnnouncement([
      jsonBlock(),
      { language: "", text: JSON.stringify(validPayload) }
    ]),
    /lebih dari satu blok JSON/
  );
});

test("menolak JSON dengan sintaks rusak", () => {
  assert.throws(
    () => ArenaAnnouncementParser.parseTelegramAnnouncement([
      { language: "json", text: "{ invalid json" }
    ]),
    /JSON tidak valid/
  );
});

test("menolak type atau version yang tidak didukung", () => {
  assert.throws(
    () => ArenaAnnouncementParser.parseTelegramAnnouncement([
      jsonBlock({ ...validPayload, type: "ordinary_message" })
    ]),
    /type.*telegram_announcement/
  );
  assert.throws(
    () => ArenaAnnouncementParser.parseTelegramAnnouncement([
      jsonBlock({ ...validPayload, version: 2 })
    ]),
    /Versi format/
  );
});

test("menolak message kosong, bukan string, atau terlalu panjang", () => {
  for (const message of ["", "   ", null, 123]) {
    assert.throws(
      () => ArenaAnnouncementParser.parseTelegramAnnouncement([
        jsonBlock({ ...validPayload, message })
      ]),
      /message/
    );
  }
  assert.throws(
    () => ArenaAnnouncementParser.parseTelegramAnnouncement([
      jsonBlock({ ...validPayload, message: "x".repeat(3901) })
    ]),
    /3.900 karakter/
  );
});

test("menolak input yang bukan array", () => {
  assert.throws(
    () => ArenaAnnouncementParser.parseTelegramAnnouncement(null),
    /Daftar blok kode/
  );
});

test("menolak karakter NUL", () => {
  assert.throws(
    () => ArenaAnnouncementParser.parseTelegramAnnouncement([
      jsonBlock({ ...validPayload, message: "bad\u0000text" })
    ]),
    /karakter yang tidak valid/
  );
});

test("menolak JSON root yang bukan object", () => {
  assert.throws(
    () => ArenaAnnouncementParser.parseTelegramAnnouncement([
      { language: "json", text: "[]" }
    ]),
    /harus berupa object/
  );
});

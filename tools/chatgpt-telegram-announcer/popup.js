const API_URL = "https://arenasystem-telegram-bot-production.up.railway.app/api/announcement";
const CHATGPT_HOSTS = new Set(["chatgpt.com", "www.chatgpt.com", "chat.openai.com"]);

const apiKeyInput = document.getElementById("apiKey");
const messageInput = document.getElementById("message");
const saveKeyButton = document.getElementById("saveKey");
const loadLatestButton = document.getElementById("loadLatest");
const sendButton = document.getElementById("send");
const statusElement = document.getElementById("status");

function setStatus(message, isError = false) {
  statusElement.textContent = message;
  statusElement.classList.toggle("warning", isError);
}

async function loadSettings() {
  const settings = await chrome.storage.local.get(["announcementApiKey"]);
  apiKeyInput.value = settings.announcementApiKey ?? "";
}

saveKeyButton.addEventListener("click", async () => {
  const apiKey = apiKeyInput.value.trim();
  if (apiKey.length < 32) {
    setStatus("API key belum valid. Pastikan kamu menyalin seluruh nilainya.", true);
    return;
  }

  try {
    await chrome.storage.local.set({ announcementApiKey: apiKey });
    setStatus("API key tersimpan secara lokal di browser.");
  } catch (error) {
    setStatus("Gagal menyimpan API key: " + error.message, true);
  }
});

loadLatestButton.addEventListener("click", async () => {
  loadLatestButton.disabled = true;
  setStatus("Mengambil jawaban ChatGPT terakhir…");

  try {
    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (!tab?.id || !tab.url) {
      throw new Error("Tab aktif tidak ditemukan. Buka percakapan ChatGPT lalu coba lagi.");
    }

    let activeUrl;
    try {
      activeUrl = new URL(tab.url);
    } catch {
      throw new Error("Alamat tab aktif tidak dapat dibaca. Buka percakapan ChatGPT lalu coba lagi.");
    }

    if (!CHATGPT_HOSTS.has(activeUrl.hostname)) {
      throw new Error("Tab aktif saat ini bukan ChatGPT (" + activeUrl.hostname + "). Buka chatgpt.com di tab ini, lalu klik Ambil jawaban ChatGPT lagi.");
    }

    const results = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: () => {
        const isVisible = (element) =>
          element instanceof HTMLElement &&
          element.getClientRects().length > 0 &&
          getComputedStyle(element).visibility !== "hidden";

        const assistantSelectors = [
          '[data-message-author-role="assistant"]',
          '[data-conversation-role="assistant"]',
          '[data-role="assistant"]',
          '[data-message-author="assistant"]',
          'article[data-turn="assistant"]',
          '[data-turn="assistant"]',
          ".agent-turn"
        ];

        let assistantMessages = [];
        let matchedSelector = "";

        for (const selector of assistantSelectors) {
          const matches = Array.from(document.querySelectorAll(selector)).filter(isVisible);
          if (matches.length > 0) {
            assistantMessages = matches;
            matchedSelector = selector;
            break;
          }
        }

        if (assistantMessages.length === 0) {
          return {
            codeBlocks: [],
            source: "not-found",
            diagnostic: "Pesan assistant tidak ditemukan."
          };
        }

        const latestAssistant = assistantMessages[assistantMessages.length - 1];
        const codeBlocks = Array.from(latestAssistant.querySelectorAll("pre code"))
          .map((code) => {
            const pre = code.closest("pre");
            const languageSource = [
              code.getAttribute("data-language"),
              pre?.getAttribute("data-language"),
              code.className,
              pre?.className
            ].filter(Boolean).join(" ");
            const languageMatch = languageSource.match(/(?:language-|lang-)([a-z0-9_+-]+)/i);
            const language = languageMatch
              ? languageMatch[1].toLowerCase()
              : (languageSource.toLowerCase().includes("json") ? "json" : "");

            return {
              language,
              text: code.innerText || code.textContent || ""
            };
          });

        return {
          codeBlocks,
          source: matchedSelector,
          diagnostic: null
        };
      }
    });

    const extracted = results?.[0]?.result;
    if (!extracted || !Array.isArray(extracted.codeBlocks)) {
      throw new Error("Struktur hasil ekstraksi tidak valid.");
    }

    if (extracted.codeBlocks.length === 0) {
      throw new Error(
        extracted.diagnostic ||
        "Tidak ditemukan blok kode JSON pada jawaban assistant terbaru. Minta ChatGPT membuat pengumuman dalam format JSON."
      );
    }

    const announcement = ArenaAnnouncementParser.parseTelegramAnnouncement(extracted.codeBlocks);
    messageInput.value = announcement.message;
    messageInput.dispatchEvent(new Event("input", { bubbles: true }));
    setStatus("Berhasil mengambil JSON pengumuman. Periksa dan edit preview sebelum mengirim.");
  } catch (error) {
    const message = error?.message || String(error);
    if (message.includes("Cannot access contents of the page") || message.includes("cannot be scripted")) {
      setStatus("Chrome menolak akses ke tab ini. Pastikan tab ChatGPT aktif, lalu reload halaman ChatGPT dan coba lagi.", true);
    } else {
      setStatus(message, true);
    }
  } finally {
    loadLatestButton.disabled = false;
  }
});

sendButton.addEventListener("click", async () => {
  const apiKey = apiKeyInput.value.trim();
  const text = messageInput.value.trim();

  if (apiKey.length < 32) {
    setStatus("Simpan API key yang valid terlebih dahulu.", true);
    return;
  }
  if (!text) {
    setStatus("Pesan masih kosong.", true);
    return;
  }
  if (text.length > 3900) {
    setStatus("Pesan terlalu panjang. Maksimal 3.900 karakter.", true);
    return;
  }

  const preview = text.length > 700 ? text.slice(0, 700) + "…" : text;
  if (!window.confirm("Kirim pesan ini ke topic Update Telegram?\n\n" + preview)) return;

  sendButton.disabled = true;
  setStatus("Mengirim pengumuman ke Telegram…");

  try {
    await chrome.storage.local.set({ announcementApiKey: apiKey });
    const response = await fetch(API_URL, {
      method: "POST",
      headers: {
        "Authorization": "Bearer " + apiKey,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ text })
    });

    const result = await response.json().catch(() => ({}));
    if (!response.ok || result.ok !== true) {
      throw new Error(result.message || "Pengiriman gagal (HTTP " + response.status + ").");
    }

    setStatus("Berhasil! Bot sudah mengirim pengumuman ke topic Telegram yang dikonfigurasi.");
  } catch (error) {
    setStatus(error.message || "Terjadi kesalahan saat mengirim pengumuman.", true);
  } finally {
    sendButton.disabled = false;
  }
});

loadSettings().catch(error => setStatus(error.message || String(error), true));

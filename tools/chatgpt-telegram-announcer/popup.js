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
        const selectedText = window.getSelection()?.toString().trim();
        if (selectedText) {
          return { text: selectedText, source: "selected-text", diagnostics: null };
        }

        const readText = (element) =>
          (element?.innerText || element?.textContent || "").trim();

        // ChatGPT has changed its message markup across UI variants.
        // Prefer the current conversation-role attributes, then older selectors.
        const selectors = [
          { selector: '[data-conversation-role="assistant"]', source: "conversation-role" },
          { selector: '[data-markdown-text-style="assistant-message"]', source: "assistant-markdown" },
          { selector: '[data-message-author-role="assistant"]', source: "message-author-role" },
          { selector: '[data-role="assistant"]', source: "data-role" },
          { selector: '[data-message-author="assistant"]', source: "message-author" },
          { selector: 'article[data-turn="assistant"]', source: "article-turn" },
          { selector: '[data-turn="assistant"] .markdown', source: "turn-markdown" },
          { selector: ".agent-turn", source: "agent-turn" }
        ];

        const counts = {};
        for (const item of selectors) {
          const nodes = Array.from(document.querySelectorAll(item.selector));
          counts[item.source] = nodes.length;
          for (let index = nodes.length - 1; index >= 0; index -= 1) {
            const text = readText(nodes[index]);
            if (text) {
              return {
                text,
                source: item.source,
                diagnostics: counts
              };
            }
          }
        }

        // Some layouts expose the conversation and turn wrappers separately.
        const root =
          document.querySelector('[data-thread-find-target="conversation"]') ||
          document.querySelector("main") ||
          document.body;

        const turns = Array.from(
          root.querySelectorAll('[data-turn-key], article[data-turn], [data-testid^="conversation-turn"]')
        );
        counts["turn-wrappers"] = turns.length;

        for (let index = turns.length - 1; index >= 0; index -= 1) {
          const turn = turns[index];
          const assistantNode = turn.querySelector(
            '[data-conversation-role="assistant"], ' +
            '[data-markdown-text-style="assistant-message"], ' +
            '[data-message-author-role="assistant"], ' +
            '[data-role="assistant"]'
          );
          const text = readText(assistantNode);
          if (text) {
            return {
              text,
              source: "assistant-in-turn-wrapper",
              diagnostics: counts
            };
          }
        }

        return {
          text: "",
          source: "not-found",
          diagnostics: {
            ...counts,
            "document-ready-state": document.readyState,
            "main-elements": document.querySelectorAll("main").length,
            "conversation-roots": document.querySelectorAll('[data-thread-find-target="conversation"]').length,
            "page-title": document.title
          }
        };
      }
    });

    const extracted = results?.[0]?.result;
    const latestText = typeof extracted === "string" ? extracted.trim() : extracted?.text?.trim();
    if (!latestText) {
      const diagnosticText = extracted?.diagnostics
        ? " Diagnostik: " + Object.entries(extracted.diagnostics)
            .map(([key, value]) => key + "=" + value)
            .join(", ")
        : "";
      throw new Error(
        "Jawaban ChatGPT belum terdeteksi pada tab aktif." +
        " Pastikan percakapan terbuka, halaman selesai dimuat, lalu coba lagi." +
        diagnosticText
      );
    }

    messageInput.value = latestText;
    messageInput.dispatchEvent(new Event("input", { bubbles: true }));

    const sourceNames = {
      "selected-text": "teks yang dipilih",
      "conversation-role": "jawaban ChatGPT (DOM terbaru)",
      "assistant-markdown": "konten jawaban ChatGPT",
      "message-author-role": "jawaban ChatGPT",
      "data-role": "jawaban ChatGPT",
      "message-author": "jawaban ChatGPT",
      "article-turn": "jawaban ChatGPT",
      "turn-markdown": "jawaban ChatGPT",
      "agent-turn": "jawaban ChatGPT",
      "assistant-in-turn-wrapper": "jawaban ChatGPT"
    };
    const sourceLabel = sourceNames[extracted?.source] || "jawaban ChatGPT terakhir";
    setStatus("Berhasil mengambil " + sourceLabel + ". Periksa dan edit preview sebelum mengirim.");
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

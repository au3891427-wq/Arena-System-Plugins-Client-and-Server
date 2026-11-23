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

        const normalizeText = (value) =>
          String(value || "")
            .replace(/\u200b/g, "")
            .replace(/\r\n?/g, "\n")
            .replace(/\n[ \t]*\n{2,}/g, "\n\n")
            .trim();

        const readText = (element) =>
          normalizeText(element?.innerText || element?.textContent || "");

        const isVisible = (element) =>
          element instanceof HTMLElement &&
          element.getClientRects().length > 0 &&
          getComputedStyle(element).visibility !== "hidden";

        // Reject UI labels that can appear in accessibility or message wrappers.
        const isUiOnlyText = (text) => {
          const compact = text.toLowerCase().replace(/[\s:：.!?,]+/g, "");
          return new Set([
            "chatgpt",
            "chatgptsaid",
            "chatgptsaidchatgpt",
            "assistant",
            "copy",
            "edit",
            "share",
            "readaloud",
            "regenerate",
            "goodresponse",
            "badresponse"
          ]).has(compact);
        };

        const roleSelectors = [
          '[data-message-author-role="assistant"]',
          '[data-conversation-role="assistant"]',
          '[data-role="assistant"]',
          '[data-message-author="assistant"]',
          'article[data-turn="assistant"]',
          '[data-turn="assistant"]',
          ".agent-turn"
        ];

        const contentSelectors = [
          '[data-markdown-text-style="assistant-message"]',
          '[data-message-content]',
          '[data-testid*="message-content"]',
          ".markdown",
          '[class*="markdown"]',
          '[class*="prose"]'
        ];

        const counts = {};
        const wrappers = new Set();

        // Gather all possible assistant containers first. Do not return the
        // first selector match: broad role wrappers can contain only UI labels.
        for (const selector of roleSelectors) {
          const nodes = Array.from(document.querySelectorAll(selector));
          counts[selector] = nodes.length;
          for (const node of nodes) {
            if (isVisible(node)) wrappers.add(node);
          }
        }

        // Some ChatGPT layouts expose the assistant role inside a turn wrapper.
        const root =
          document.querySelector('[data-thread-find-target="conversation"]') ||
          document.querySelector("main") ||
          document.body;
        const turnWrappers = Array.from(
          root.querySelectorAll('[data-turn-key], article[data-turn], [data-testid^="conversation-turn"]')
        );
        counts["turn-wrappers"] = turnWrappers.length;

        const assistantSelector = roleSelectors.slice(0, 6).join(", ");
        for (const turn of turnWrappers) {
          const assistantNode = turn.matches(assistantSelector)
            ? turn
            : turn.querySelector(assistantSelector);
          if (assistantNode && isVisible(assistantNode)) wrappers.add(assistantNode);
        }

        // The explicit assistant-markdown attribute is reliable enough to
        // inspect globally as a fallback, even when wrapper attributes change.
        const explicitMarkdown = Array.from(
          document.querySelectorAll('[data-markdown-text-style="assistant-message"]')
        );
        counts["explicit-assistant-markdown"] = explicitMarkdown.length;
        for (const node of explicitMarkdown) {
          if (isVisible(node)) wrappers.add(node);
        }

        const chooseContentFrom = (wrapper) => {
          const candidates = new Map();

          const addCandidate = (node, source, priority) => {
            if (!isVisible(node)) return;
            const text = readText(node);
            if (!text || isUiOnlyText(text)) return;

            const score = priority * 100000 + Math.min(text.length, 50000);
            const previous = candidates.get(node);
            if (!previous || score > previous.score) {
              candidates.set(node, { text, source, score });
            }
          };

          for (let index = 0; index < contentSelectors.length; index += 1) {
            const selector = contentSelectors[index];
            const priority = 100 - index * 10;
            if (wrapper.matches(selector)) {
              addCandidate(wrapper, selector, priority);
            }
            for (const node of wrapper.querySelectorAll(selector)) {
              addCandidate(node, selector, priority);
            }
          }

          // Last-resort fallback: use the assistant wrapper only when it
          // contains substantive text rather than a short ChatGPT UI label.
          addCandidate(wrapper, "assistant-wrapper", 1);

          return Array.from(candidates.values()).sort((a, b) => b.score - a.score)[0] || null;
        };

        const orderedWrappers = Array.from(wrappers).sort((a, b) => {
          const position = a.compareDocumentPosition(b);
          if (position & 4) return -1;
          if (position & 2) return 1;
          return 0;
        });

        // Search from the newest assistant container backwards.
        for (let index = orderedWrappers.length - 1; index >= 0; index -= 1) {
          const candidate = chooseContentFrom(orderedWrappers[index]);
          if (candidate) {
            return {
              text: candidate.text,
              source: candidate.source,
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

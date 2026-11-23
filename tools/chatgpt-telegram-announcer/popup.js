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
          return { text: selectedText, source: "selected-text" };
        }

        const readText = (element) => (element?.innerText || element?.textContent || "").trim();

        // Selector utama pada container pesan ChatGPT.
        const assistantMessages = Array.from(
          document.querySelectorAll('[data-message-author-role="assistant"]')
        );

        for (let index = assistantMessages.length - 1; index >= 0; index -= 1) {
          const text = readText(assistantMessages[index]);
          if (text) return { text, source: "assistant-message" };
        }

        // Fallback untuk layout yang membungkus setiap giliran percakapan dengan test-id.
        const turns = Array.from(document.querySelectorAll('main [data-testid^="conversation-turn-"]'));
        for (let index = turns.length - 1; index >= 0; index -= 1) {
          const turn = turns[index];
          const assistantNode = turn.matches('[data-message-author-role="assistant"]')
            ? turn
            : turn.querySelector('[data-message-author-role="assistant"]');
          const text = readText(assistantNode);
          if (text) return { text, source: "assistant-turn" };
        }

        // Fallback terakhir untuk Markdown di area percakapan utama.
        const markdownBlocks = Array.from(document.querySelectorAll('main .markdown.prose, main .markdown'));
        for (let index = markdownBlocks.length - 1; index >= 0; index -= 1) {
          const text = readText(markdownBlocks[index]);
          if (text) return { text, source: "markdown-fallback" };
        }

        return { text: "", source: "not-found" };
      }
    });

    const extracted = results?.[0]?.result;
    const latestText = typeof extracted === "string" ? extracted.trim() : extracted?.text?.trim();
    if (!latestText) {
      throw new Error("Elemen jawaban ChatGPT tidak ditemukan. Pastikan percakapan sudah selesai dimuat. Alternatif: blok teks jawaban di halaman ChatGPT, lalu klik tombol ini lagi.");
    }

    messageInput.value = latestText;
    messageInput.dispatchEvent(new Event("input", { bubbles: true }));
    const sourceLabel = extracted?.source === "selected-text" ? "teks yang dipilih" : "jawaban ChatGPT terakhir";
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

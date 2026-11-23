const API_URL = "https://arenasystem-telegram-bot-production.up.railway.app/api/announcement";

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
  await chrome.storage.local.set({ announcementApiKey: apiKey });
  setStatus("API key tersimpan secara lokal di browser.");
});

loadLatestButton.addEventListener("click", async () => {
  loadLatestButton.disabled = true;
  setStatus("Mengambil jawaban ChatGPT terakhir…");
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab?.id || !tab.url?.startsWith("https://chatgpt.com/")) {
      throw new Error("Buka percakapan ChatGPT terlebih dahulu.");
    }
    const results = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: () => {
        const selected = window.getSelection()?.toString().trim();
        if (selected) return selected;

        const messages = Array.from(
          document.querySelectorAll('[data-message-author-role="assistant"]')
        );
        for (let index = messages.length - 1; index >= 0; index -= 1) {
          const message = messages[index];
          const blocks = Array.from(message.querySelectorAll("pre code, pre"));
          const candidate = blocks.length
            ? blocks[blocks.length - 1].innerText
            : message.innerText;
          if (candidate?.trim()) return candidate.trim();
        }
        return "";
      }
    });

    const latestText = results?.[0]?.result?.trim() ?? "";
    if (!latestText) {
      throw new Error("Jawaban ChatGPT tidak ditemukan. Pilih teks jawaban secara manual, lalu coba lagi.");
    }
    messageInput.value = latestText;
    setStatus("Draft berhasil diambil. Periksa dan edit pesannya sebelum mengirim.");
  } catch (error) {
    setStatus(error.message || "Gagal mengambil jawaban ChatGPT.", true);
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
  const preview = text.length > 700 ? `${text.slice(0, 700)}…` : text;
  if (!window.confirm(`Kirim pesan ini ke topic Update Telegram?\n\n${preview}`)) return;

  sendButton.disabled = true;
  setStatus("Mengirim pengumuman ke Telegram…");
  try {
    await chrome.storage.local.set({ announcementApiKey: apiKey });
    const response = await fetch(API_URL, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${apiKey}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ text })
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || result.ok !== true) {
      throw new Error(result.message || `Pengiriman gagal (HTTP ${response.status}).`);
    }
    setStatus("Berhasil! Bot sudah mengirim pengumuman ke topic Telegram yang dikonfigurasi.");
  } catch (error) {
    setStatus(error.message || "Terjadi kesalahan saat mengirim pengumuman.", true);
  } finally {
    sendButton.disabled = false;
  }
});

loadSettings().catch(error => setStatus(error.message, true));

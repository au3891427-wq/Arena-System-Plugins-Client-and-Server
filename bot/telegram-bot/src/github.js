const releaseCache = new Map();
const CACHE_TTL_MS = 60 * 1000;

export async function getLatestStableRelease(repository) {
  const cached = releaseCache.get(repository);
  if (cached && cached.expiresAt > Date.now()) {
    return cached.value;
  }

  const url = "https://api.github.com/repos/" + repository + "/releases?per_page=20";
  const response = await fetch(url, {
    headers: {
      accept: "application/vnd.github+json",
      "user-agent": "ArenaSystem-Telegram-Assistant",
      "x-github-api-version": "2022-11-28"
    },
    signal: AbortSignal.timeout(12000)
  });

  if (!response.ok) {
    throw new Error("GitHub releases lookup failed with HTTP " + response.status + ".");
  }

  const releases = await response.json();
  if (!Array.isArray(releases)) {
    throw new Error("GitHub releases API returned an unexpected response.");
  }

  const release = releases.find((item) =>
    item &&
    item.draft === false &&
    item.prerelease === false &&
    Array.isArray(item.assets) &&
    item.assets.some((asset) =>
      asset.state === "uploaded" &&
      typeof asset.browser_download_url === "string" &&
      /\.jar$/i.test(asset.name)
    )
  ) || null;

  releaseCache.set(repository, {
    value: release,
    expiresAt: Date.now() + CACHE_TTL_MS
  });

  return release;
}

export function getJarAssets(release) {
  if (!release || !Array.isArray(release.assets)) {
    return [];
  }

  return release.assets.filter((asset) =>
    asset.state === "uploaded" &&
    typeof asset.browser_download_url === "string" &&
    /\.jar$/i.test(asset.name)
  );
}

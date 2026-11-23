#!/usr/bin/env bash
set -euo pipefail

EXPECTED_RELEASE_URL="https://github.com/$GITHUB_REPOSITORY/releases/tag/Bukkit"

if [[ "${EVENT_NAME:-}" == "release" ]]; then
  release_tag="${EVENT_RELEASE_TAG:-}"
else
  release_tag="${INPUT_RELEASE_TAG:-}"
fi

if [[ "$release_tag" != "Bukkit" ]]; then
  echo "::error::Only the stable Bukkit release or a versioned ArenaSystem server release can update this manifest."
  exit 1
fi

release_json="$(gh release view "$release_tag" --repo "$GITHUB_REPOSITORY" --json tagName,isDraft,isPrerelease,assets)"
if [[ "$(jq -r '.tagName' <<< "$release_json")" != "$release_tag" ]]; then
  echo "::error::GitHub returned a different release tag."
  exit 1
fi
if [[ "$(jq -r '.isDraft' <<< "$release_json")" == "true" ||
      "$(jq -r '.isPrerelease' <<< "$release_json")" == "true" ]]; then
  echo "::error::Only published stable releases can update the server manifest."
  exit 1
fi

server_pattern='^arenaSystem_Bukkit-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*))?\.jar$'
server_count="$(jq --arg pattern "$server_pattern" '[.assets[].name | select(test($pattern))] | length' <<< "$release_json")"
if [[ "$server_count" != "1" ]]; then
  echo "::error::Release must contain exactly one arenaSystem_Bukkit-X.Y.Z[.N].jar asset."
  exit 1
fi

server_name="$(jq --arg pattern "$server_pattern" -r '[.assets[].name | select(test($pattern))][0]' <<< "$release_json")"
version="${server_name#arenaSystem_Bukkit-}"
version="${version%.jar}"
if [[ ! "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9]*)(\.(0|[1-9][0-9]*))?$ ]]; then
  echo "::error::Server asset has an invalid stable semantic version."
  exit 1
fi

asset_dir="$RUNNER_TEMP/arenasystem-server-release"
mkdir -p "$asset_dir"
gh release download "$release_tag" --repo "$GITHUB_REPOSITORY" \
  --pattern "$server_name" --dir "$asset_dir"

server_file="$asset_dir/$server_name"
[[ -s "$server_file" ]] || { echo "::error::Server JAR download is missing or empty."; exit 1; }

plugin_yml="$RUNNER_TEMP/plugin.yml"
unzip -p "$server_file" plugin.yml > "$plugin_yml"
[[ -s "$plugin_yml" ]] || { echo "::error::Server JAR does not contain plugin.yml."; exit 1; }

plugin_name="$(sed -nE 's/^name:[[:space:]]*([^[:space:]]+).*$/\1/p' "$plugin_yml" | head -n 1 | tr -d "'\"")"
plugin_version="$(sed -nE "s/^version:[[:space:]]*['\"]?([0-9]+(\\.[0-9]+){2,3})['\"]?[[:space:]]*$/\\1/p" "$plugin_yml" | head -n 1)"

if [[ "$plugin_name" != "ArenaSystem" || "$plugin_version" != "$version" ]]; then
  echo "::error::plugin.yml metadata does not match the server release asset name."
  exit 1
fi

server_hash="$(sha256sum "$server_file" | cut -d ' ' -f 1)"
if [[ ! "$server_hash" =~ ^[a-f0-9]{64}$ ]]; then
  echo "::error::Could not calculate a valid SHA-256 hash."
  exit 1
fi

download_url="https://github.com/$GITHUB_REPOSITORY/releases/download/$release_tag/$server_name"
manifest_path="arenasystem-server-update-manifest.json"
manifest_tmp="$RUNNER_TEMP/arenasystem-server-update-manifest.json"
jq -n \
  --arg version "$version" \
  --arg releaseTag "$release_tag" \
  --arg releaseUrl "$EXPECTED_RELEASE_URL" \
  --arg downloadUrl "$download_url" \
  --arg sha256 "$server_hash" \
  '{
    schemaVersion: 1,
    channel: "stable",
    plugin: "ArenaSystem",
    version: $version,
    releaseTag: $releaseTag,
    releaseUrl: $releaseUrl,
    downloadUrl: $downloadUrl,
    sha256: $sha256
  }' > "$manifest_tmp"

if [[ -f "$manifest_path" ]]; then
  current_version="$(jq -r '.version // empty' "$manifest_path")"
  current_hash="$(jq -r '.sha256 // empty' "$manifest_path")"
  if [[ -n "$current_version" ]]; then
    if [[ ! "$current_version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*))?$ ]]; then
      echo "::error::Existing server manifest contains an invalid version."
      exit 1
    fi

    if [[ "$current_version" == "$version" ]]; then
      if [[ "$current_hash" == "$server_hash" ]]; then
        echo "Server manifest already points to this verified JAR."
        exit 0
      fi
      echo "::error::Refusing to replace stable server version $version with different artifact bytes."
      exit 1
    fi

    highest="$(printf '%s\n%s\n' "$current_version" "$version" | sort -V | tail -n 1)"
    if [[ "$highest" == "$current_version" ]]; then
      echo "::error::Refusing to move the server update manifest backwards from $current_version to $version."
      exit 1
    fi
  fi
fi

cp "$manifest_tmp" "$manifest_path"
jq -e '.schemaVersion == 1 and .channel == "stable" and .plugin == "ArenaSystem"' "$manifest_path" >/dev/null

if [[ "${DRY_RUN:-false}" == "true" ]]; then
  echo "Dry-run completed: server manifest validated; no commit or push performed."
  exit 0
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add "$manifest_path"
if git diff --cached --quiet; then
  echo "No server manifest changes to commit."
  exit 0
fi
git commit -m "Publish ArenaSystem Server $version update manifest"
git push origin HEAD:main

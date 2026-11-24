# Offline BM25 Fallback — Design

## Intent
Keep `/ask` useful when the user's laptop and its local 9Router instance are offline. The Java bot already computes an embedded BM25 answer before calling 9Router and returns it when the remote request fails; this change improves which verified knowledge section BM25 retrieves.

## Goals
- Improve retrieval for common Indonesian/English phrasings about overview, server plugin, client mod, handshake, updates, releases/downloads, installation/compatibility, and classes/abilities.
- Keep search aliases internal to indexing; do not include them in the user-facing answer or the context sent to 9Router.
- Add no new ArenaSystem facts. Existing verified section bodies remain the only answer content.

## Non-goals
- No second hosted AI provider, new API key, database, or runtime service.
- No change to 9Router availability, timeout, or deployment configuration.
- No claims about undocumented Minecraft/Paper/Fabric versions or class statistics.

## Approaches
1. **Embedded BM25 with search-only aliases (chosen):** add phrasing aliases per existing knowledge section and index them separately from the returned body. It works without the laptop or an external provider and preserves the current factual boundary.
2. Add another hosted AI provider: can produce fluent answers but adds external dependency, credentials, and potential cost; unnecessary for the requested offline fallback.
3. Keep the current implementation unchanged: already falls back, but broad phrasings can retrieve generic sections and miss the exact relevant section.

## Design
Add a `Pertanyaan terkait:` metadata line to each Markdown knowledge section. `LocalAssistant` parses that line into a search-only field on its document record. BM25 ranks on title + body + aliases, while responses contain only title + verified body. Tests cover retrieval of installation/compatibility and release/download questions and verify aliases are not exposed.

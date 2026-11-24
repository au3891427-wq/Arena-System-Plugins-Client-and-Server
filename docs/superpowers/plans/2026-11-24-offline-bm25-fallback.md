# Offline BM25 Fallback Implementation Plan

> **For agentic workers:** Execute natively in this session, using a test-first workflow. Check each task before moving on.

**Goal:** Improve the embedded BM25 fallback so `/ask` can retrieve existing verified documentation when local 9Router is unavailable.

**Architecture:** Keep the existing local-first answer path. Add per-section search aliases to the Markdown knowledge file, parse them into a private search-only field, and include them in BM25 scoring without returning them in answer text.

**Tech Stack:** Java 21, Maven 3.9+, JUnit 5, Markdown knowledge resource.

**Spec:** `docs/superpowers/specs/2026-11-24-offline-bm25-fallback-design.md`

## Global Constraints
- Add no new factual claims about ArenaSystem.
- Keep aliases out of answer text and 9Router context.
- Preserve the existing 9Router-to-BM25 fallback behavior and all unrelated bot features.
- No new dependencies or environment variables.

## Review Focus
- Alias lines are indexed but never shown in answers.
- Questions without a matching alias continue to use the existing safe unknown-answer response.
- Existing class-detail boundaries remain intact.
- All knowledge sections remain loadable when a section has no aliases.
- BM25 relevance improves without allowing an alias-only line to become factual answer content.

---

### Task 1: Retrieval regression tests

**Files:**
- Modify: `bot/java-bot/src/test/java/com/arenasystem/bot/LocalAssistantTest.java`

- [ ] Add tests showing installation/compatibility and release/download phrasings retrieve the matching section.
- [ ] Assert the internal alias metadata is absent from rendered answers.
- [ ] Run the focused tests and confirm they fail because the current index has no search-only aliases.

### Task 2: Search-only alias indexing

**Files:**
- Modify: `bot/java-bot/src/main/java/com/arenasystem/bot/LocalAssistant.java`
- Modify: `bot/java-bot/src/main/resources/knowledge/arenasystem.md`

- [ ] Extend the document record and Markdown parser to capture `Pertanyaan terkait:` lines separately from body text.
- [ ] Include aliases in the token set used for BM25 scoring only.
- [ ] Add grounded wording variants for each existing knowledge section without changing the section facts.
- [ ] Run focused and full Maven tests; verify answer bodies do not contain alias metadata.

### Task 3: Review and handoff

- [ ] Re-read the diff and confirm only the assistant retrieval path and its knowledge/tests/docs changed.
- [ ] Open a pull request; do not merge or deploy production without a separate explicit request.

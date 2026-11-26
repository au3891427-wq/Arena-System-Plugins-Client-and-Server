# ChatGPT Telegram JSON Announcement — Implementation Plan

**Goal:** Restrict extension extraction to one validated JSON announcement block in the latest assistant message.
**Architecture:** DOM injection returns only code-block language and source text; a standalone parser validates the contract and returns only `message`. The existing preview, confirmation, and Railway API request remain unchanged.
**Tech Stack:** Chrome Extension Manifest V3, JavaScript, Node.js built-in test runner.
**Spec:** `tools/chatgpt-telegram-announcer/DESIGN.md`

## Global Constraints
- Keep API URL, bearer authentication, API request body `{"text":"..."}`, and configured Telegram topic unchanged.
- Never read full assistant message text or selected text.
- Require exactly one JSON-labelled code block in the newest assistant message.
- Require `type=telegram_announcement`, `version=1`, and a valid `message` of at most 3,900 characters.

## Review Focus
- Latest assistant response has no JSON block: fail closed, do not use an older response.
- Latest assistant response has multiple JSON blocks: reject ambiguity.
- JSON is malformed or uses unsupported schema: show a useful error, do not populate preview.
- Surrounding prose or other code blocks: ensure none of their content reaches preview.
- ChatGPT DOM selector changes: report extraction failure rather than broadening to whole-page text.

---

### Task 1: Isolated announcement parser

**Files:**
- Create: `announcement-parser.js`
- Create: `announcement-parser.test.js`

- [ ] Test valid single JSON block and ensure only `message` is returned.
- [ ] Test no JSON block, multiple JSON blocks, malformed JSON, wrong type/version, empty/non-string/oversized message, and non-array input.
- [ ] Run `node --test announcement-parser.test.js`; all tests must pass.

### Task 2: Restrict DOM extraction and integrate parser

**Files:**
- Modify: `popup.js`
- Modify: `popup.html`
- Modify: `manifest.json`
- Modify: `README.md`

- [ ] Extract only `pre code` blocks from the newest visible assistant message; identify the language from code/pre attributes and classes.
- [ ] Remove selected-text and full-message fallback paths.
- [ ] Load the parser before `popup.js`; populate preview only with the validated `message`.
- [ ] Keep send confirmation and existing API contract intact; bump extension version to 1.1.0.
- [ ] Document JSON schema, fail-closed behavior, and unchanged install/send flow.
- [ ] Run parser tests and JavaScript syntax checks; inspect the final diff.

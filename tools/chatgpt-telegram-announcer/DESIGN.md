# JSON Announcement Extraction — Design

## Intent
Make the ChatGPT browser extension forward only an explicitly structured Telegram announcement, never the whole latest assistant response.

## Chosen approach
Read code blocks from the newest visible assistant message, accept exactly one block labelled `json`, validate its payload in a standalone parser, and preview only its `message` field. This avoids dependence on surrounding prose and keeps DOM extraction separate from validation.

## Contract
- `type` must equal `telegram_announcement`.
- `version` must equal integer `1`.
- `message` must be a non-empty string of at most 3,900 characters and contain no NUL.
- Zero or multiple JSON blocks, malformed JSON, and invalid fields abort extraction.
- Ordinary conversations do not trigger sending; the user still reviews and confirms delivery.

## Boundaries
- Keep the existing Railway endpoint, bearer authentication, and configured Telegram destination unchanged.
- Do not use selected text or full-message text as a fallback.
- No automatic sending, background monitoring, or backend changes.

package com.arenasystem.bot;

final class AnnouncementText {
    static final int MAX_LENGTH = 3900;

    private AnnouncementText() {
    }

    static String normalize(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Announcement text is required.");
        }
        String normalized = text.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Announcement text must not be empty.");
        }
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Announcement text must not exceed " + MAX_LENGTH + " characters."
            );
        }
        if (normalized.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Announcement text contains an invalid character.");
        }
        return normalized;
    }
}

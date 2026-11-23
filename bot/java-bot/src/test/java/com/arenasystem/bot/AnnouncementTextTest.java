package com.arenasystem.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnnouncementTextTest {
    @Test
    void trimsOuterWhitespaceAndPreservesInternalFormatting() {
        assertEquals("Update\n\nVersi 0.1.6", AnnouncementText.normalize("  Update\n\nVersi 0.1.6  "));
    }

    @Test
    void rejectsBlankText() {
        assertThrows(IllegalArgumentException.class, () -> AnnouncementText.normalize("  \n "));
    }

    @Test
    void rejectsTextAboveTelegramSafeLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> AnnouncementText.normalize("a".repeat(AnnouncementText.MAX_LENGTH + 1)));
    }

    @Test
    void rejectsNullText() {
        assertThrows(IllegalArgumentException.class, () -> AnnouncementText.normalize(null));
    }

    @Test
    void rejectsNullByte() {
        assertThrows(IllegalArgumentException.class, () -> AnnouncementText.normalize("hello" + (char) 0 + "world"));
    }
}

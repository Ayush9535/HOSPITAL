package com.hms.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoEmojiValidatorTest {

    private final NoEmojiValidator validator = new NoEmojiValidator();

    @Test
    void allowsPlainSpecialAndNonLatinText() {
        assertTrue(validator.isValid(null, null));
        assertTrue(validator.isValid("", null));
        assertTrue(validator.isValid("John O'Brien", null));
        assertTrue(validator.isValid("221B, Baker St. #4 - flat", null)); // special chars OK in free text
        assertTrue(validator.isValid("user@example.com", null));
        assertTrue(validator.isValid("नमस्ते", null)); // Devanagari letters
    }

    @Test
    void rejectsEmojiAndPictographs() {
        assertFalse(validator.isValid("hello 😀", null)); // 😀
        assertFalse(validator.isValid("👍", null));       // 👍
        assertFalse(validator.isValid("flag 🇮🇳", null)); // 🇮🇳
        assertFalse(validator.isValid("star ⭐", null));        // ⭐
        assertFalse(validator.isValid("check ✔️", null)); // ✔️
    }
}

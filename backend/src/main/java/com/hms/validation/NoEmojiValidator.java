package com.hms.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Scans a String codepoint-by-codepoint and rejects it if it contains any emoji / pictographic
 * symbol. Null and empty are treated as valid (presence is enforced separately by @NotBlank).
 */
public class NoEmojiValidator implements ConstraintValidator<NoEmoji, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        int i = 0;
        while (i < value.length()) {
            int cp = value.codePointAt(i);
            if (isEmoji(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    private boolean isEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)   // emoticons, pictographs, transport, supplemental
                || (cp >= 0x2600 && cp <= 0x27BF)  // misc symbols + dingbats
                || (cp >= 0x2B00 && cp <= 0x2BFF)  // misc symbols and arrows
                || (cp >= 0xFE00 && cp <= 0xFE0F)  // variation selectors (emoji styling)
                || cp == 0x200D                    // zero-width joiner (emoji sequences)
                || cp == 0x20E3                    // combining enclosing keycap
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF); // regional indicators (flags)
    }
}

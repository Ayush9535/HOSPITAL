package com.hms.util;

/**
 * Sanitises user-controlled values before they are written to logs.
 *
 * <p>Prevents log injection / log forging (CWE-117): without this, a value containing newline
 * characters (e.g. an attacker-supplied email or id) could inject fake, attacker-controlled lines
 * into the log, corrupting the audit trail. Replacing CR/LF keeps every logged value on one line.
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /**
     * Returns {@code value} as a string with CR and LF replaced by {@code '_'} so it cannot break
     * out of its log line. {@code null} becomes the literal {@code "null"}.
     */
    public static String clean(Object value) {
        if (value == null) {
            return "null";
        }
        return String.valueOf(value).replace('\n', '_').replace('\r', '_');
    }
}

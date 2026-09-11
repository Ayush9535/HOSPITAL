package com.hms.util;

import org.springframework.web.multipart.MultipartFile;

/**
 * Strict validation for CSV file uploads. Rejects anything that is not a small, plausibly-CSV
 * file BEFORE it is parsed. Throws IllegalArgumentException on failure, which GlobalExceptionHandler
 * maps to a 400 with the given (safe) message.
 *
 * The extension is the primary gate; the content type is checked leniently because browsers label
 * CSVs inconsistently (text/csv, application/vnd.ms-excel, text/plain, application/octet-stream).
 * The importer that follows still validates the row content itself.
 */
public final class CsvUploads {

    /** Hard cap on upload size (defence in depth on top of spring.servlet.multipart.max-file-size). */
    public static final long MAX_BYTES = 5L * 1024 * 1024; // 5 MB

    private CsvUploads() {
    }

    public static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("File is too large. Maximum size is 5 MB.");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".csv")) {
            throw new IllegalArgumentException("Only .csv files are allowed.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !isCsvContentType(contentType)) {
            throw new IllegalArgumentException("Only CSV files are allowed.");
        }
    }

    private static boolean isCsvContentType(String contentType) {
        String c = contentType.toLowerCase();
        return c.contains("csv")
                || c.equals("text/plain")
                || c.equals("application/vnd.ms-excel")
                || c.equals("application/octet-stream");
    }
}

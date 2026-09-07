package com.hms.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/** A saved surgery form returned to the frontend (data parsed back to a map). */
@Data
public class SurgeryFormView {
    private String formType;
    private Map<String, Object> data;
    private LocalDateTime savedAt;
    private Long surgeryId;
    private Integer version;
    /** Non-null once signed. A signed form renders read-only; an edit creates a new version. */
    private LocalDateTime signedAt;
    private Long signedByUserId;
}

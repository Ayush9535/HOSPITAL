package com.hms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CreateSupportTicketRequest - Request DTO for secure support ticket creation.
 * Excludes system-managed audit attributes (id, hospitalId, hospitalName, adminName, status, resolvedAt).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateSupportTicketRequest {
    private String subject;
    private String message;
    private String priority; // LOW, MEDIUM, HIGH
}

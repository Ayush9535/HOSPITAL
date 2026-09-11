package com.hms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A clinical document a patient brought with them.
 *
 * <p>An outside blood report, a scan from another hospital, a photographed prescription. It
 * belongs to the patient and the facility first; the OPD or admission it happened to be handed
 * over at is optional context, because a report the patient carried in may have nothing to do
 * with any encounter this system knows about.
 *
 * <p>Nothing here asserts the document was produced or verified by this HMS. It is what somebody
 * handed to the desk, recorded as such.
 *
 * <p>{@code storageKey} is an opaque handle for the storage layer, never a filesystem path and
 * never sent to a browser. Reading a document goes through the authenticated endpoint, which
 * resolves it tenant-scoped before any bytes move.
 */
@Data
@Entity
@Table(name = "patient_documents")
public class PatientDocument {

    public static final String PATHOLOGY_REPORT = "PATHOLOGY_REPORT";
    public static final String RADIOLOGY_REPORT = "RADIOLOGY_REPORT";
    public static final String PRESCRIPTION = "PRESCRIPTION";
    public static final String DISCHARGE_SUMMARY = "DISCHARGE_SUMMARY";
    public static final String REFERRAL = "REFERRAL";
    public static final String INSURANCE_DOCUMENT = "INSURANCE_DOCUMENT";
    public static final String OTHER = "OTHER";

    public static final java.util.Set<String> TYPES = java.util.Set.of(
            PATHOLOGY_REPORT, RADIOLOGY_REPORT, PRESCRIPTION, DISCHARGE_SUMMARY,
            REFERRAL, INSURANCE_DOCUMENT, OTHER);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 64)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    /** Optional context: where the document was handed over, if that is known. */
    @Column(name = "opd_id")
    private Long opdId;

    @Column(name = "ipd_admission_id")
    private Long ipdAdmissionId;

    @Column(name = "document_type", nullable = false, length = 40)
    private String documentType;

    @Column(nullable = false, length = 200)
    private String title;

    /** When the report was produced elsewhere, which is rarely when it was uploaded here. */
    @Column(name = "report_date")
    private LocalDate reportDate;

    /** Who produced it -- another hospital, a lab, a clinic. Free text; we cannot verify it. */
    @Column(length = 200)
    private String source;

    @Column(length = 1000)
    private String notes;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    /** Opaque storage handle. Never a path, never returned to a client. */
    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "uploaded_by_user_id")
    private Long uploadedByUserId;

    /**
     * False once archived. The row and the stored bytes both remain -- a clinical document is a
     * record, and archiving says it should not appear in the working list, not that it never
     * existed.
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "archived_by_user_id")
    private Long archivedByUserId;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Column(name = "archive_reason", length = 500)
    private String archiveReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

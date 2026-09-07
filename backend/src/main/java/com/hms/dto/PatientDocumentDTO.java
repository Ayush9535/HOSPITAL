package com.hms.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A document as a browser is allowed to see it.
 *
 * <p>Deliberately no storageKey and no path of any kind: the client asks for content by publicId
 * and the server decides whether it may have it. Handing out the storage handle would create a
 * second way to name a file, which is the beginning of a way around the first.
 */
public class PatientDocumentDTO {

    private String publicId;
    private String documentType;
    private String title;
    private LocalDate reportDate;
    private String source;
    private String notes;
    private String originalFileName;
    private String mimeType;
    private Long fileSizeBytes;
    private Long opdId;
    private Long ipdAdmissionId;
    private String uploadedBy;
    private LocalDateTime createdAt;

    public PatientDocumentDTO(com.hms.entity.PatientDocument d, String uploadedBy) {
        this.publicId = d.getPublicId();
        this.documentType = d.getDocumentType();
        this.title = d.getTitle();
        this.reportDate = d.getReportDate();
        this.source = d.getSource();
        this.notes = d.getNotes();
        this.originalFileName = d.getOriginalFileName();
        this.mimeType = d.getMimeType();
        this.fileSizeBytes = d.getFileSizeBytes();
        this.opdId = d.getOpdId();
        this.ipdAdmissionId = d.getIpdAdmissionId();
        this.uploadedBy = uploadedBy;
        this.createdAt = d.getCreatedAt();
    }

    public String getPublicId() { return publicId; }
    public String getDocumentType() { return documentType; }
    public String getTitle() { return title; }
    public LocalDate getReportDate() { return reportDate; }
    public String getSource() { return source; }
    public String getNotes() { return notes; }
    public String getOriginalFileName() { return originalFileName; }
    public String getMimeType() { return mimeType; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public Long getOpdId() { return opdId; }
    public Long getIpdAdmissionId() { return ipdAdmissionId; }
    public String getUploadedBy() { return uploadedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

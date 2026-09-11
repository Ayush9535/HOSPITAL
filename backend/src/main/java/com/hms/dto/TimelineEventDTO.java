package com.hms.dto;

import java.time.LocalDateTime;

/**
 * One entry on a PatientClinicalTimeline (CLIN-P1).
 *
 * A read-model row, not a persisted record: every field is populated by reading an already
 * authoritative clinical entity (Opd, IpdAdmission, VitalsRecord, Surgery, ...) and is never
 * itself written back anywhere. {@code sourceType}/{@code sourceId} let a client jump to the
 * record this event describes; the timeline never duplicates the record's own content beyond a
 * short human-readable summary.
 */
public class TimelineEventDTO {
    private LocalDateTime timestamp;
    /** e.g. OPD_REGISTERED, CONSULTATION, PRESCRIPTION, IPD_ADMITTED, WARD_TRANSFER,
     *  NURSE_ASSIGNED, VITALS, NURSING_NOTE, SUGAR_CHART, MEDICATION_ADMINISTERED,
     *  SURGERY_REQUESTED, SURGERY_APPROVED, SURGERY_SCHEDULED, SURGERY_PRE_OP,
     *  SURGERY_STARTED, SURGERY_COMPLETED, SURGERY_POSTPONED, SURGERY_CANCELLED,
     *  SURGERY_CLOSED, SURGICAL_TEAM_ASSIGNED, ANAESTHESIA_CLEARANCE, WHO_SIGN_IN,
     *  WHO_TIME_OUT, WHO_SIGN_OUT, INTRA_OP_MILESTONE, SURGERY_FORM_SIGNED,
     *  RECOVERY_ADMITTED, RECOVERY_OBSERVATION, RECOVERY_DISCHARGED, DISCHARGE_SUMMARY,
     *  DOCUMENT_UPLOADED, DOCUMENT_ARCHIVED */
    private String eventType;
    /** Which encounter/admission/surgery this event belongs to, so a client can group or
     *  deep-link without re-deriving it. */
    private String encounterType; // OPD | IPD | SURGERY
    private Long encounterId;
    private Long performedByUserId;
    private String performedByName;
    private String performedByRole;
    private String summary;
    /** The entity type and id backing this row -- the "reference, don't duplicate" contract. */
    private String sourceType;
    private Long sourceId;

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getEncounterType() { return encounterType; }
    public void setEncounterType(String encounterType) { this.encounterType = encounterType; }
    public Long getEncounterId() { return encounterId; }
    public void setEncounterId(Long encounterId) { this.encounterId = encounterId; }
    public Long getPerformedByUserId() { return performedByUserId; }
    public void setPerformedByUserId(Long performedByUserId) { this.performedByUserId = performedByUserId; }
    public String getPerformedByName() { return performedByName; }
    public void setPerformedByName(String performedByName) { this.performedByName = performedByName; }
    public String getPerformedByRole() { return performedByRole; }
    public void setPerformedByRole(String performedByRole) { this.performedByRole = performedByRole; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
}

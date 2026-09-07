package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * SurgeryForm - a saved OT/NABH form (generic JSON store; layouts live on the frontend).
 *
 * Scoped to the SURGERY, not the admission. It used to be unique on
 * (ipd_admission_id, form_type), so a second procedure in the same admission
 * silently overwrote the first procedure's signed consent and WHO checklist.
 *
 * Once {@code signedAt} is set the row is immutable: an edit supersedes it
 * ({@code isCurrent = null}) and inserts a new row at {@code version + 1}. The
 * unique key covers {@code isCurrent} so exactly one current row exists per
 * (surgery, formType) while any number of superseded versions may coexist --
 * MySQL treats NULLs in a unique key as distinct.
 */
@Entity
@Table(name = "surgery_forms",
        uniqueConstraints = @UniqueConstraint(name = "UK_surgery_form_surgery_type",
                columnNames = {"surgery_id", "form_type", "is_current"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SurgeryForm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** Null for a day-care procedure with no IPD admission. */
    @Column(name = "ipd_admission_id")
    private Long ipdAdmissionId;

    @Column(name = "surgery_id")
    private Long surgeryId;

    @Column(name = "form_type", nullable = false, length = 60)
    private String formType;

    @Column(name = "data_json", columnDefinition = "longtext")
    private String dataJson;

    @Column(name = "saved_by_user_id")
    private Long savedByUserId;

    @Column(name = "performed_by_nurse_id")
    private Long performedByNurseId;

    /** 1-based. A superseded row keeps its own version number. */
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    /**
     * TRUE for the live row; NULL once superseded. Never FALSE -- the unique key
     * relies on NULL being distinct so superseded rows do not collide.
     */
    @Column(name = "is_current")
    private Boolean isCurrent = Boolean.TRUE;

    /** Set once the form is signed. A signed row is never updated in place. */
    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "signed_by_user_id")
    private Long signedByUserId;

    /** Who typed it in -- distinct from performedByNurseId, who did the care. */
    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) this.publicId = java.util.UUID.randomUUID().toString();
    }
}

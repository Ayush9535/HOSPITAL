package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * A per-hospital, reusable phrase a doctor can insert with one click instead of
 * typing. fieldType selects which consultation field it belongs to:
 * "TREATMENT_NOTES" (e.g. "Avoid oily food") or "SYMPTOMS" (e.g. "Fever x 3 days").
 * The service/controller/repository are fieldType-agnostic, so new field types
 * work without schema or query changes.
 */
@Entity
@Table(name = "consultation_note_presets")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationNotePreset {

    public static final String FIELD_TYPE_TREATMENT_NOTES = "TREATMENT_NOTES";
    public static final String FIELD_TYPE_SYMPTOMS = "SYMPTOMS";
    public static final String FIELD_TYPE_DIAGNOSIS = "DIAGNOSIS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /**
     * Owning doctor. NULL = shared preset (visible to every doctor in the
     * hospital); a set value scopes the preset privately to that doctor.
     */
    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(name = "field_type", nullable = false, length = 30)
    private String fieldType;

    @Column(name = "text", nullable = false, length = 255)
    private String text;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * SurgeryStateTransition - append-only record of every change to a case's status.
 *
 * This is the table the OT module's evidence rests on: NABH asks who moved the case,
 * when, and why, and every board metric (turnover, on-time start, cancellation rate by
 * reason) is a query over these rows. Nothing may write surgeries.status without
 * writing one of these.
 *
 * A step the hospital's policy does not require is auto-satisfied by the system, and
 * that still produces a row -- with actorKind = SYSTEM. A silent auto-approval leaving
 * no trace is indistinguishable from a bug, and reads as a human approval that never
 * happened.
 */
@Entity
@Table(name = "surgery_state_transitions", indexes = {
        @Index(name = "idx_sst_surgery", columnList = "surgery_id,created_at"),
        @Index(name = "idx_sst_hospital_to", columnList = "hospital_id,to_status,created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SurgeryStateTransition {

    public static final String ACTOR_USER = "USER";
    public static final String ACTOR_SYSTEM = "SYSTEM";

    /** Written when a policy grants a step nobody is required to perform. */
    public static final String REASON_AUTO_APPROVED = "AUTO_APPROVED_BY_POLICY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "surgery_id", nullable = false)
    private Long surgeryId;

    /** Null on creation: the case had no prior status. */
    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;

    /** Null when actorKind = SYSTEM. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_kind", nullable = false, length = 10)
    private String actorKind = ACTOR_USER;

    @Column(name = "reason_code", length = 60)
    private String reasonCode;

    @Column(name = "reason_text", length = 255)
    private String reasonText;

    /** Free-form detail, e.g. a reschedule's old and new slot. */
    @Column(name = "payload_json", columnDefinition = "text")
    private String payloadJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

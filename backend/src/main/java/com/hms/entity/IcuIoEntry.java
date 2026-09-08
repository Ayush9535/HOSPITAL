package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * IcuIoEntry - one fluid intake or output event (ICU Phase 5).
 *
 * <p>An event stream, not an observation. A vitals row is ONE reading at ONE instant, but a
 * patient has several intakes and outputs between two readings, so I/O cannot live on
 * {@code vitals_records}: forcing it there would either drop entries or invent observation times
 * nobody recorded.
 *
 * <p><b>Source of truth (ICU-5 D-2).</b> This table is authoritative for ICU fluid balance and for
 * the NABH I/O chart. {@code VitalsRecord.urine_output_ml} is a SEPARATE, independent
 * point-in-time observation: it is never synchronised into this table in either direction, never
 * contributes to a balance, and is never rendered as an entry here. The two are different clinical
 * statements - "output measured over this interval" versus "urine output observed at this instant"
 * - and merging them would fabricate events no clinician recorded.
 *
 * <p>The balance is never stored. It is always {@code SUM(volume_ml)} over these rows, so it
 * cannot drift from the events it summarises.
 */
@Entity
@Table(name = "icu_io_entry", indexes = {
        @Index(name = "idx_icu_io_admission", columnList = "ipd_admission_id"),
        @Index(name = "idx_icu_io_hospital", columnList = "hospital_id"),
        @Index(name = "idx_icu_io_occurred", columnList = "ipd_admission_id,occurred_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IcuIoEntry {

    public static final String INTAKE = "INTAKE";
    public static final String OUTPUT = "OUTPUT";

    /**
     * The five routes are exactly the columns the hospital's existing NABH chart already prints.
     * Nothing clinical is invented here; adding a route is a deliberate edit, not an omission.
     */
    public static final String ROUTE_IV_FLUIDS = "IV_FLUIDS";
    public static final String ROUTE_ORAL = "ORAL";
    public static final String ROUTE_RYLES_ASPIRATION = "RYLES_ASPIRATION";
    public static final String ROUTE_URINE = "URINE";
    public static final String ROUTE_VOMIT = "VOMIT";

    public static final List<String> INTAKE_ROUTES = List.of(ROUTE_IV_FLUIDS, ROUTE_ORAL);
    public static final List<String> OUTPUT_ROUTES =
            List.of(ROUTE_RYLES_ASPIRATION, ROUTE_URINE, ROUTE_VOMIT);

    /** Whether this route belongs to the given direction. */
    public static boolean routeMatchesDirection(String direction, String route) {
        if (INTAKE.equals(direction)) return INTAKE_ROUTES.contains(route);
        if (OUTPUT.equals(direction)) return OUTPUT_ROUTES.contains(route);
        return false;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "ipd_admission_id", nullable = false)
    private Long ipdAdmissionId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "direction", nullable = false, length = 6)
    private String direction;

    @Column(name = "route", nullable = false, length = 30)
    private String route;

    @Column(name = "volume_ml", nullable = false)
    private Integer volumeMl;

    /** When the fluid actually went in or out - not when someone typed it. */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** Free text, e.g. the fluid name. A coded formulary is a clinical decision, not ours. */
    @Column(name = "notes", length = 255)
    private String notes;

    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;

    @Column(name = "performed_by_nurse_id")
    private Long performedByNurseId;

    /**
     * The entry this row corrects. An entry recorded during an ICU stay is never edited in place:
     * a correction writes a NEW row pointing here and the original stays readable, exactly as
     * ICU-4 does for vitals.
     */
    @Column(name = "supersedes_io_entry_id")
    private Long supersedesIoEntryId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) this.publicId = java.util.UUID.randomUUID().toString();
        if (this.isActive == null) this.isActive = true;
    }
}

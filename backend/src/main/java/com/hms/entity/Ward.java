package com.hms.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "wards")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Ward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ward_id")
    private Long wardId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "ward_name", nullable = false)
    private String wardName;

    @Column(name = "bed_price", nullable = false)
    private java.math.BigDecimal bedPrice;

    @Column(name = "total_beds", nullable = false)
    private Integer totalBeds;

    @Column(name = "floor_number")
    private Integer floorNumber;

    @Column(name = "incharge_nurse_id")
    private Long inchargeNurseId;

    /**
     * Ward classification from {@code CareUnitRegistry} — GENERAL, ICU, MICU, SICU, NICU,
     * PICU, CCU or HDU (ICU Phase 2). Defaults to GENERAL so every pre-existing ward keeps
     * behaving exactly as before. The ward's NAME stays free text; this is what the ICU board
     * filters on, so a unit is never identified by a substring of its name.
     */
    @Column(name = "unit_type", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) NOT NULL DEFAULT 'GENERAL'")
    private String unitType = "GENERAL";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

package com.hms.dto.icu;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Start an infusion, titrate it, or correct a recorded rate (ICU Phase 6). */
@Data
public class IcuInfusionRequest {

    /** Required when starting; ignored when titrating or correcting. */
    private Long ipdAdmissionId;

    /** Optional (D-2): an ICU drip is often started before the prescription is entered. */
    private Long prescriptionId;

    /** Required when starting. */
    private String medicineName;

    /** The rate. Required when starting, titrating and correcting. */
    private BigDecimal rateValue;

    /** A key from InfusionRateUnitRegistry. Stored as entered; never converted. */
    private String rateUnit;

    /** When this rate began applying, or when the infusion started. Defaults to now. */
    private LocalDateTime effectiveFrom;

    /** Stop only. */
    private String stopReason;

    /** Optional; required when Separate Nurse Login is OFF, as for vitals and I/O. */
    private Long performedByNurseId;
}

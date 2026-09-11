package com.hms.dto.icu;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/** Record a ventilator snapshot, or correct one (ICU Phase 7). */
@Data
public class IcuVentilatorRequest {

    /** Required when recording; ignored when correcting (the original names the admission). */
    private Long ipdAdmissionId;

    /** INVASIVE / NIV / OFF. Mandatory (D-1) — it is never inferred from the values. */
    private String ventilationStatus;

    /**
     * Parameter values keyed by {@code param_key}, e.g. {@code {"fio2": 60, "mode": "VC"}}.
     *
     * <p>Keys the hospital has disabled, and keys nothing defines, are dropped server-side. May be
     * empty — an OFF row carries no settings.
     */
    private Map<String, Object> values;

    /** When the setting applied. Defaults to now. */
    private LocalDateTime observedAt;

    private String note;

    /** Optional; required when Separate Nurse Login is OFF, as for vitals, I/O and infusions. */
    private Long performedByNurseId;
}

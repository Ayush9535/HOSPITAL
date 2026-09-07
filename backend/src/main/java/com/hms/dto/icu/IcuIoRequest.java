package com.hms.dto.icu;

import lombok.Data;

import java.time.LocalDateTime;

/** One fluid intake or output event to record, or the corrected values for an existing one. */
@Data
public class IcuIoRequest {

    /** Required on create; ignored on correction (the original's admission is authoritative). */
    private Long ipdAdmissionId;

    /** INTAKE or OUTPUT. */
    private String direction;

    /** One of the five NABH routes; must belong to {@link #direction}. */
    private String route;

    private Integer volumeMl;

    /** When the fluid actually went in or out. Defaults to now. */
    private LocalDateTime occurredAt;

    /** Free text, e.g. the fluid name. */
    private String notes;

    /** Optional; required when Separate Nurse Login is OFF, as for vitals. */
    private Long performedByNurseId;
}

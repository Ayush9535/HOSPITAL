package com.hms.dto.icu;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/** Record a severity score, or correct one (ICU Phase 8). */
@Data
public class IcuSeverityScoreRequest {

    /** Required when recording; ignored when correcting (the original names the admission). */
    private Long ipdAdmissionId;

    /** A SeverityScoreRegistry key: SOFA or APACHE_II. */
    private String scoreType;

    /**
     * Component subscores keyed by component key, e.g. {@code {"respiratory": 2, "renal": 1}}.
     *
     * <p>Used by SOFA. Ignored for a total-only score such as APACHE II (D-4). Keys the score type
     * does not define are dropped; a value outside its component's range is rejected.
     */
    private Map<String, Object> components;

    /**
     * The total, for a total-only score. For SOFA it is ignored: the total is the sum of the
     * components the clinician entered, never a separately typed figure that could disagree.
     */
    private Integer totalScore;

    /** When the patient was scored. Defaults to now. */
    private LocalDateTime scoredAt;

    private String note;

    /** Optional; required when Separate Nurse Login is OFF, as for vitals, I/O and ventilator. */
    private Long performedByNurseId;
}

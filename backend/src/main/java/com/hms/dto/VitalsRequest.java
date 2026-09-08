package com.hms.dto;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Create/update payload for a vitals record. All measurements are optional
 * individually, but at least one must be present (enforced in the service).
 */
@Data
public class VitalsRequest {
    @Positive
    private Long ipdAdmissionId;      // required on create; ignored on update

    private LocalDateTime recordedAt; // optional; defaults to now

    @DecimalMin(value = "0.0", message = "temperature must be non-negative")
    private BigDecimal temperature;

    @Min(value = 0, message = "pulse must be non-negative")
    private Integer pulse;

    @Min(value = 0, message = "bpSystolic must be non-negative")
    private Integer bpSystolic;

    @Min(value = 0, message = "bpDiastolic must be non-negative")
    private Integer bpDiastolic;

    @Min(value = 0, message = "respiratoryRate must be non-negative")
    private Integer respiratoryRate;

    @Min(value = 0, message = "spo2 must be non-negative")
    @Max(value = 100, message = "spo2 must be at most 100")
    private Integer spo2;

    @DecimalMin(value = "0.0", message = "weight must be non-negative")
    private BigDecimal weight;

    @Min(value = 0, message = "painScore must be between 0 and 10")
    @Max(value = 10, message = "painScore must be between 0 and 10")
    private Integer painScore;

    @Size(max = 1000)
    @NoEmoji
    private String remarks;

    @Positive
    private Long performedByNurseId; // optional; required when Separate Nurse Login is OFF

    // ── ICU Phase 4. Optional everywhere; a ward form simply omits them.
    private Integer mapMmhg;
    private Integer cvpCmh2o;
    private Integer urineOutputMl;
    private Integer gcsEye;
    private Integer gcsVerbal;
    private Integer gcsMotor;
}

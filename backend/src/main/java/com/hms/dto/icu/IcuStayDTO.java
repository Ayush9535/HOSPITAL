package com.hms.dto.icu;

import com.hms.entity.IcuStay;
import lombok.Data;

import java.time.LocalDateTime;

/** One ICU stay, as the API returns it (ICU Phase 3). */
@Data
public class IcuStayDTO {
    private String publicId;
    private Long ipdAdmissionId;
    private Long patientId;
    private Long wardId;
    private String status;
    private String source;
    private Long sourceRefId;
    private LocalDateTime admittedAt;
    private String admissionReason;
    private Long intensivistDoctorId;
    /** Resolved for display; null when unset or no longer resolvable. */
    private String intensivistName;
    private String disposition;
    private LocalDateTime dischargedAt;

    public static IcuStayDTO of(IcuStay s, String intensivistName) {
        IcuStayDTO d = new IcuStayDTO();
        d.setPublicId(s.getPublicId());
        d.setIpdAdmissionId(s.getIpdAdmissionId());
        d.setPatientId(s.getPatientId());
        d.setWardId(s.getWardId());
        d.setStatus(s.getStatus());
        d.setSource(s.getSource());
        d.setSourceRefId(s.getSourceRefId());
        d.setAdmittedAt(s.getAdmittedAt());
        d.setAdmissionReason(s.getAdmissionReason());
        d.setIntensivistDoctorId(s.getIntensivistDoctorId());
        d.setIntensivistName(intensivistName);
        d.setDisposition(s.getDisposition());
        d.setDischargedAt(s.getDischargedAt());
        return d;
    }
}

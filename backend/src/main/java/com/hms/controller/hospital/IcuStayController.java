package com.hms.controller.hospital;

import com.hms.dto.icu.IcuStayDTO;
import com.hms.entity.HospitalType;
import com.hms.security.RequireModule;
import com.hms.security.TenantType;
import com.hms.service.hospital.icu.IcuStayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * IcuStayController - reads the ICU stay record, and edits the two fields a movement cannot
 * infer (ICU Phase 3).
 *
 * <p><b>There is deliberately no create and no close endpoint.</b> A stay opens and closes only
 * as a consequence of an existing IPD admission, transfer or discharge. Exposing "create ICU
 * stay" would let the record disagree with the bed the patient is actually in — the same
 * single-source-of-truth rule that kept ICU-2 read-only.
 *
 * <p>Hospital-only and declared in {@code ControllerModules}: an undeclared controller makes
 * {@code FacilityAccessAspect} treat it as having no module and let it through.
 */
@RestController
@RequestMapping("/hospital/icu")
@RequireModule("ICU")
@TenantType(HospitalType.HOSPITAL)
@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST','NURSE','NURSE_INCHARGE')")
public class IcuStayController {

    @Autowired
    private IcuStayService icuStayService;

    @GetMapping("/stays/{publicId}")
    public ResponseEntity<IcuStayDTO> getStay(@PathVariable String publicId) {
        return ResponseEntity.ok(icuStayService.viewByPublicId(publicId));
    }

    /** Every stay for an admission, ACTIVE and CLOSED, newest first. */
    @GetMapping("/admissions/{ipdId}/stays")
    public ResponseEntity<List<IcuStayDTO>> history(@PathVariable Long ipdId) {
        return ResponseEntity.ok(icuStayService.viewHistoryFor(ipdId));
    }

    /** Null or absent doctorId clears the intensivist. */
    @PutMapping("/stays/{publicId}/intensivist")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR')")
    public ResponseEntity<IcuStayDTO> setIntensivist(@PathVariable String publicId,
                                                     @RequestBody(required = false) Map<String, Long> body) {
        Long doctorId = body == null ? null : body.get("doctorId");
        return ResponseEntity.ok(icuStayService.setIntensivistAndView(publicId, doctorId));
    }

    @PutMapping("/stays/{publicId}/admission-reason")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR')")
    public ResponseEntity<IcuStayDTO> setAdmissionReason(@PathVariable String publicId,
                                                         @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("admissionReason");
        return ResponseEntity.ok(icuStayService.setAdmissionReasonAndView(publicId, reason));
    }
}

package com.hms.controller.hospital;

import com.hms.entity.HospitalType;
import com.hms.security.RequireModule;
import com.hms.security.TenantType;
import com.hms.service.hospital.ot.SurgeryExecutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Theatre execution: WHO checklist, clinical milestones and the operative note.
 * HOSPITAL tenant only, OT-gated, never aliased onto /clinic.
 */
@RestController
@RequestMapping("/hospital/ot/surgeries/{surgeryId}")
@TenantType(HospitalType.HOSPITAL)
@RequireModule("OT")
public class SurgeryExecutionController {

    @Autowired
    private SurgeryExecutionService service;

    @GetMapping("/milestones")
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> milestones(@PathVariable Long surgeryId) {
        return ResponseEntity.ok(service.milestones(surgeryId));
    }

    @PostMapping("/milestones")
    @PreAuthorize("hasAnyAuthority('OT_START','OT_COMPLETE','OT_PRE_OP')")
    public ResponseEntity<?> recordMilestone(@PathVariable Long surgeryId, @RequestBody Map<String, Object> body) {
        String milestone = (String) body.get("milestone");
        LocalDateTime at = body.get("occurredAt") == null ? null
                : LocalDateTime.parse(String.valueOf(body.get("occurredAt")));
        Long nurseId = body.get("performedByNurseId") == null ? null
                : Long.valueOf(String.valueOf(body.get("performedByNurseId")));
        return ResponseEntity.ok(service.recordMilestone(surgeryId, milestone, at, nurseId, (String) body.get("note")));
    }

    @GetMapping("/who-checklist")
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> checklist(@PathVariable Long surgeryId) {
        return ResponseEntity.ok(service.checklist(surgeryId));
    }

    /** Sign one WHO phase: SIGN_IN, TIME_OUT or SIGN_OUT. */
    @PostMapping("/who-checklist/{phase}/sign")
    @PreAuthorize("hasAuthority('OT_TIME_OUT')")
    public ResponseEntity<?> signPhase(@PathVariable Long surgeryId, @PathVariable String phase,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Boolean siteMarked = b.get("siteMarked") == null ? null : Boolean.valueOf(String.valueOf(b.get("siteMarked")));
        Boolean countsCorrect = b.get("countsCorrect") == null ? null : Boolean.valueOf(String.valueOf(b.get("countsCorrect")));
        return ResponseEntity.ok(service.signPhase(surgeryId, phase, siteMarked, countsCorrect));
    }

    @PostMapping("/operative-note")
    @PreAuthorize("hasAuthority('OT_COMPLETE')")
    public ResponseEntity<?> operativeNote(@PathVariable Long surgeryId, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.saveOperativeNote(surgeryId, body.get("note")));
    }
}

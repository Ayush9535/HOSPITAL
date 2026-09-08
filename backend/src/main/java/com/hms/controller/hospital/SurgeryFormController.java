package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.SaveSurgeryFormRequest;
import com.hms.entity.HospitalType;
import com.hms.security.RequireModule;
import com.hms.security.TenantType;
import com.hms.service.hospital.SurgeryFormService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * SurgeryFormController - OT/NABH surgery forms (OT module, Phase 2).
 * HOSPITAL tenant only, OT-gated. Nurse fills/saves; reads open to
 * nurse/doctor/admin.
 */
@RestController
@RequestMapping("/hospital/surgery-forms")
@TenantType(HospitalType.HOSPITAL)
@RequireModule("OT")
public class SurgeryFormController {

    @Autowired
    private SurgeryFormService service;

    @PostMapping
    @PreAuthorize("hasAuthority('OT_FORM_EDIT')")
    public ResponseEntity<?> save(@Valid @RequestBody SaveSurgeryFormRequest req) {
        return ResponseEntity.ok(service.save(req));
    }

    @GetMapping("/admission/{admissionId}/{formType}")
    @PreAuthorize("hasAuthority('OT_FORM_VIEW')")
    public ResponseEntity<?> get(@PathVariable Long admissionId, @PathVariable String formType) {
        return ResponseEntity.ok(service.get(admissionId, formType));
    }

    @GetMapping("/admission/{admissionId}")
    @PreAuthorize("hasAuthority('OT_FORM_VIEW')")
    public ResponseEntity<?> listSaved(@PathVariable Long admissionId) {
        return ResponseEntity.ok(service.listSavedTypes(admissionId));
    }

    // --- procedure-scoped: the only unambiguous addressing when an admission
    // --- carries more than one surgery. Prefer these over /admission/**.

    @GetMapping("/surgery/{surgeryId}/{formType}")
    @PreAuthorize("hasAuthority('OT_FORM_VIEW')")
    public ResponseEntity<?> getBySurgery(@PathVariable Long surgeryId, @PathVariable String formType) {
        return ResponseEntity.ok(service.getBySurgery(surgeryId, formType));
    }

    @GetMapping("/surgery/{surgeryId}")
    @PreAuthorize("hasAuthority('OT_FORM_VIEW')")
    public ResponseEntity<?> listSavedBySurgery(@PathVariable Long surgeryId) {
        return ResponseEntity.ok(service.listSavedTypesBySurgery(surgeryId));
    }

    @GetMapping("/surgery/{surgeryId}/{formType}/versions")
    @PreAuthorize("hasAuthority('OT_FORM_VIEW')")
    public ResponseEntity<?> versions(@PathVariable Long surgeryId, @PathVariable String formType) {
        return ResponseEntity.ok(service.versions(surgeryId, formType));
    }

    @PostMapping("/surgery/{surgeryId}/{formType}/sign")
    @PreAuthorize("hasAuthority('OT_FORM_EDIT')")
    public ResponseEntity<?> sign(@PathVariable Long surgeryId, @PathVariable String formType) {
        return ResponseEntity.ok(service.sign(surgeryId, formType));
    }
}

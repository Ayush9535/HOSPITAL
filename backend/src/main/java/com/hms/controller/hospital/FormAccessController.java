package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.FormAccessRequest;
import com.hms.security.SecurityContextHelper;
import com.hms.service.hospital.FormAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * FormAccessController - Files & Access config (Phase 1). Admin lists/updates
 * the per-hospital form access; any hospital staff role reads the effective
 * verdict map for the forms UI. Hospital-tenant only.
 */
@RestController
@RequestMapping({"/hospital/form-access", "/clinic/form-access"})
public class FormAccessController {

    @Autowired private FormAccessService formAccessService;
    @Autowired private SecurityContextHelper securityHelper;

    @GetMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(formAccessService.list());
    }

    @PutMapping("/{formKey}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> update(@PathVariable String formKey, @Valid @RequestBody FormAccessRequest req) {
        return ResponseEntity.ok(formAccessService.update(formKey, req));
    }

    @GetMapping("/effective")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','NURSE','NURSE_INCHARGE','RECEPTIONIST')")
    public ResponseEntity<?> effective() {
        return ResponseEntity.ok(formAccessService.effectiveForRole(securityHelper.getCurrentUserRole()));
    }
}

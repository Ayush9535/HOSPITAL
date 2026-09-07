package com.hms.controller.hospital;

import com.hms.entity.HospitalType;
import com.hms.security.RequireModule;
import com.hms.security.TenantType;
import com.hms.service.hospital.ot.RecoveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hospital-wide recovery board. Unlike RecoveryController (one case at a time, nested under
 * /surgeries/{surgeryId}/recovery), this is the read model that guarantees a completed patient is
 * always discoverable: it lists every active recovery episode AND every COMPLETED surgery that
 * has not yet been admitted to one, so a failed or skipped admission cannot make a patient
 * disappear from the operational view.
 */
@RestController
@RequestMapping("/hospital/ot/recovery")
@TenantType(HospitalType.HOSPITAL)
@RequireModule("OT")
public class RecoveryBoardController {
    @Autowired
    private RecoveryService service;

    @GetMapping("/board")
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> board() {
        return ResponseEntity.ok(service.board());
    }
}

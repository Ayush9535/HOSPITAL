package com.hms.controller.hospital;

import com.hms.dto.BedResponse;
import com.hms.repository.BedRepository;
import com.hms.security.NurseInchargeGuard;
import com.hms.service.hospital.BedStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import com.hms.service.hospital.BedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/hospital/beds", "/clinic/beds", "/pharmacy/beds"})
@Validated
@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST','PHARMACIST')")
public class BedController {

    private final BedService bedService;

    @Autowired private BedStatusService bedStatusService;
    @Autowired private NurseInchargeGuard nurseInchargeGuard;
    @Autowired private BedRepository bedRepository;

    public BedController(BedService bedService) {
        this.bedService = bedService;
    }

    // Nursing Mgmt: only Available beds may be selected for admission
    @GetMapping("/available")
    public ResponseEntity<List<BedResponse>> getAvailable(@RequestParam(value = "ward_id", required = false) Long wardId) {
        return ResponseEntity.ok(bedService.getAvailableBeds(wardId));
    }

    @PostMapping("/{bedId}/cleaned")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> markCleaned(@PathVariable Long bedId, @RequestBody(required = false) com.hms.dto.BedStatusChangeRequest req) {
        com.hms.entity.Bed bed = requireBedForWardAccess(bedId);
        if (!com.hms.entity.BedStatus.CLEANING.equals(bed.getStatus()))
            throw new IllegalArgumentException("Only a bed awaiting cleaning can be marked cleaned");
        bedStatusService.change(bedId, com.hms.entity.BedStatus.AVAILABLE, remarksOf(req, "Marked cleaned"));
        return ResponseEntity.ok(java.util.Map.of("message", "Bed marked cleaned"));
    }

    @PostMapping("/{bedId}/maintenance")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> markMaintenance(@PathVariable Long bedId, @RequestBody(required = false) com.hms.dto.BedStatusChangeRequest req) {
        com.hms.entity.Bed bed = requireBedForWardAccess(bedId);
        if (com.hms.entity.BedStatus.OCCUPIED.equals(bed.getStatus()))
            throw new IllegalArgumentException("An occupied bed cannot be put under maintenance");
        bedStatusService.change(bedId, com.hms.entity.BedStatus.MAINTENANCE, remarksOf(req, "Under maintenance"));
        return ResponseEntity.ok(java.util.Map.of("message", "Bed under maintenance"));
    }

    @PostMapping("/{bedId}/available")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> markAvailable(@PathVariable Long bedId, @RequestBody(required = false) com.hms.dto.BedStatusChangeRequest req) {
        com.hms.entity.Bed bed = requireBedForWardAccess(bedId);
        if (!com.hms.entity.BedStatus.MAINTENANCE.equals(bed.getStatus()))
            throw new IllegalArgumentException("Only a bed under maintenance can be returned to available");
        bedStatusService.change(bedId, com.hms.entity.BedStatus.AVAILABLE, remarksOf(req, "Back to available"));
        return ResponseEntity.ok(java.util.Map.of("message", "Bed available"));
    }

    @GetMapping("/{bedId}/history")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> bedHistory(@PathVariable Long bedId) {
        requireBedForWardAccess(bedId);
        return ResponseEntity.ok(bedStatusService.history(bedId));
    }

    private com.hms.entity.Bed requireBedForWardAccess(Long bedId) {
        com.hms.entity.Bed bed = bedRepository.findById(bedId)
                .orElseThrow(() -> new IllegalArgumentException("Bed not found"));
        nurseInchargeGuard.assertWardAccess(bed.getWardId());
        return bed;
    }
    private String remarksOf(com.hms.dto.BedStatusChangeRequest req, String fallback) {
        return (req != null && req.getRemarks() != null && !req.getRemarks().isBlank()) ? req.getRemarks() : fallback;
    }
}

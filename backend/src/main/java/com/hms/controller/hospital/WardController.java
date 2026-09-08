package com.hms.controller.hospital;

import com.hms.dto.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import com.hms.service.hospital.WardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/hospital/wards", "/clinic/wards", "/pharmacy/wards"})
@Validated
// Reads are shared: reception and doctors need ward/bed lists to admit and manage IPD.
// Every mutation below overrides this with HOSPITAL_ADMIN only.
@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST','PHARMACIST')")
public class WardController {

    private final WardService wardService;

    public WardController(WardService wardService) {
        this.wardService = wardService;
    }

    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    @PostMapping
    public ResponseEntity<WardResponse> createWard(@Valid @RequestBody CreateWardRequest req) {
        return ResponseEntity.ok(wardService.createWard(req));
    }

    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    @PostMapping("/bulk")
    public ResponseEntity<List<WardResponse>> bulkCreate(@Valid @RequestBody BulkCreateWardsRequest req) {
        return ResponseEntity.ok(wardService.bulkCreate(req));
    }

    @GetMapping
    public ResponseEntity<List<WardResponse>> getAll() {
        return ResponseEntity.ok(wardService.getAllWards());
    }

    // Admission/bed-selection sees any ward with an Available bed. A ward without a
    // Nurse Incharge is valid (UNSTAFFED) and must not be hidden from admission.
    @GetMapping("/for-admission")
    public ResponseEntity<List<WardResponse>> getForAdmission() {
        return ResponseEntity.ok(wardService.getWardsForAdmission());
    }

    @GetMapping("/{wardId}/beds")
    public ResponseEntity<List<BedResponse>> getBeds(@PathVariable("wardId") Long wardId) {
        return ResponseEntity.ok(wardService.getBedsForWard(wardId));
    }

    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    @PutMapping("/{wardId}")
    public ResponseEntity<WardResponse> updateWard(@PathVariable("wardId") Long wardId, @Valid @RequestBody UpdateWardRequest req) {
        return ResponseEntity.ok(wardService.updateWard(wardId, req));
    }

    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    @DeleteMapping("/{wardId}")
    public ResponseEntity<Void> deleteWard(@PathVariable("wardId") Long wardId) {
        wardService.deleteWard(wardId);
        return ResponseEntity.noContent().build();
    }
}

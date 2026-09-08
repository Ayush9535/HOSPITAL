package com.hms.controller.hospital;

import com.hms.security.RequireModule;
import com.hms.dto.icu.IcuAlertThresholdRequest;
import com.hms.service.hospital.icu.IcuAlertThresholdService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * IcuAlertThresholdController - alert threshold configuration (ICU Phase 9).
 *
 * <p>Admin-only, hospital-only (clinics have no IPD). There is no clinical endpoint: the
 * evaluator runs server-side inside the vitals write and is never reachable from a client.
 *
 * <p>No DELETE — disabling keeps the numbers a hospital chose.
 */
@RestController
@RequestMapping("/hospital/icu/alert-thresholds")
@RequireModule("ICU")
public class IcuAlertThresholdController {

    @Autowired
    private IcuAlertThresholdService thresholdService;

    @GetMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(thresholdService.list());
    }

    @PutMapping("/{metricKey}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> upsert(@PathVariable String metricKey,
                                    @Valid @RequestBody IcuAlertThresholdRequest req) {
        return ResponseEntity.ok(thresholdService.upsert(metricKey, req));
    }
}

package com.hms.controller.hospital;

import com.hms.security.RequireModule;
import com.hms.dto.icu.IcuScoreTypeSettingRequest;
import com.hms.service.hospital.icu.ScoreTypeSettingService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * IcuScoreTypeSettingController - which severity scores a hospital uses (ICU Phase 8, D-2).
 *
 * <p><b>Hospital-only</b>: clinics have no IPD, so there is no {@code /clinic} alias.
 *
 * <p>Deliberately smaller than ICU-7's parameter API. There is no POST and no DELETE, because a
 * hospital chooses whether it runs SOFA — not what SOFA is.
 */
@RestController
@RequestMapping("/hospital/icu/score-types")
@RequireModule("ICU")
public class IcuScoreTypeSettingController {

    @Autowired
    private ScoreTypeSettingService scoreTypeSettingService;

    /** Admin view: every score type with its effective enabled flag, components and ranges. */
    @GetMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(scoreTypeSettingService.list());
    }

    @PutMapping("/{scoreType}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> toggle(@PathVariable String scoreType,
                                    @Valid @RequestBody IcuScoreTypeSettingRequest req) {
        return ResponseEntity.ok(scoreTypeSettingService.toggle(scoreType, req));
    }
}

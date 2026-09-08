package com.hms.controller.hospital;

import com.hms.security.RequireModule;
import com.hms.dto.icu.IcuVentilatorParameterRequest;
import com.hms.service.hospital.icu.VentilatorParameterService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * IcuVentilatorParameterController - the ventilator parameter catalogue (ICU Phase 7, D-5).
 *
 * <p>Mirrors {@code VitalSettingsController}: admin-only writes, a staff-readable
 * {@code /enabled} list that drives charting. <b>Hospital-only</b> by decision (D-7) — clinics
 * have no IPD, so there is no {@code /clinic} alias here.
 *
 * <p>There is deliberately <b>no DELETE</b>. A parameter is disabled, not removed, so every key
 * ever charted still resolves to a name.
 */
@RestController
@RequestMapping("/hospital/icu/ventilator-parameters")
@RequireModule("ICU")
public class IcuVentilatorParameterController {

    @Autowired
    private VentilatorParameterService parameterService;

    /** Admin view: every parameter with its effective enabled flag. */
    @GetMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(parameterService.list());
    }

    /** What may be charted now. Read by every clinical role that fills the ventilator chart. */
    @GetMapping("/enabled")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST','NURSE','NURSE_INCHARGE')")
    public ResponseEntity<?> enabled() {
        return ResponseEntity.ok(parameterService.enabledParameters());
    }

    /** Toggle and/or edit display name, unit and category. The key is never rewritten. */
    @PutMapping("/{paramKey}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> update(@PathVariable String paramKey,
                                    @Valid @RequestBody IcuVentilatorParameterRequest req) {
        return ResponseEntity.ok(parameterService.update(paramKey, req));
    }

    @PostMapping("/custom")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> addCustom(@Valid @RequestBody IcuVentilatorParameterRequest req) {
        return ResponseEntity.ok(parameterService.addCustom(req));
    }

    /** The controlled mode values. Parameter names are configurable; mode values are not. */
    @GetMapping("/modes")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST','NURSE','NURSE_INCHARGE')")
    public ResponseEntity<?> modes() {
        return ResponseEntity.ok(parameterService.modes());
    }
}

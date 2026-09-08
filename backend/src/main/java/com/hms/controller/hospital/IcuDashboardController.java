package com.hms.controller.hospital;

import com.hms.dto.icu.IcuDashboardDTO;
import com.hms.entity.HospitalType;
import com.hms.security.RequireModule;
import com.hms.security.TenantType;
import com.hms.service.hospital.icu.CareUnitRegistry;
import com.hms.service.hospital.icu.IcuBoardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IcuDashboardController - read-only ICU capacity views (ICU Phase 2).
 *
 * <p><b>Hospital-only, deliberately.</b> There is no {@code /clinic} or {@code /pharmacy} alias:
 * critical care is a hospital capability, and adding an alias here is what would put ICU inside
 * {@code ClinicPharmacyIsolationTest}'s golden set. {@link TenantType} makes that a server-side
 * rule rather than a convention about which paths the frontend happens to call.
 *
 * <p>This controller is also declared in {@code ControllerModules}. That is not optional:
 * {@code FacilityAccessAspect} treats an undeclared controller as having no module and lets it
 * through, so omitting the declaration would silently expose ICU to other facility types.
 *
 * <p>Every endpoint is a GET. ICU Phase 2 adds no write path of any kind — bed status and
 * admissions keep their existing owners.
 */
@RestController
@RequestMapping("/hospital/icu")
@RequireModule("ICU")
@TenantType(HospitalType.HOSPITAL)
@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST','NURSE','NURSE_INCHARGE')")
public class IcuDashboardController {

    @Autowired
    private IcuBoardService icuBoardService;

    /**
     * The full board — totals, units and every bed row — as one snapshot.
     * Row content is narrowed per role inside the service.
     */
    @GetMapping("/board")
    public ResponseEntity<IcuDashboardDTO> getBoard() {
        return ResponseEntity.ok(icuBoardService.getBoard());
    }

    /** Totals and per-unit counts without the bed grid, for the dashboard's lighter refresh. */
    @GetMapping("/board/units")
    public ResponseEntity<IcuDashboardDTO> getUnits() {
        return ResponseEntity.ok(icuBoardService.getSummary());
    }

    /** The ward unit-type catalogue, for the ward form's classification selector. */
    @GetMapping("/unit-types")
    public ResponseEntity<List<CareUnitRegistry.UnitType>> getUnitTypes() {
        return ResponseEntity.ok(icuBoardService.unitTypes());
    }
}

package com.hms.controller.hospital;

import com.hms.dto.DashboardOverviewDTO;
import com.hms.entity.HospitalType;
import com.hms.security.TenantType;
import com.hms.service.hospital.DashboardRange;
import com.hms.service.hospital.HospitalDashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Hospital Admin Overview.
 *
 * <p>Deliberately not on HospitalStatsController. That controller is declared under REPORTS in
 * {@code ControllerModules}, and an overview screen is not a report — a hospital that never bought
 * Reports still has to be able to open its own front page. This endpoint is CORE, and the modules
 * gate the individual blocks inside the response instead.
 *
 * <p>For the same reason there is no {@code @RequireModule} here. That annotation is all-or-nothing
 * for the whole handler; the dashboard needs OPD to decide one block while still answering for a
 * tenant without it. The service resolves capabilities per block and simply omits what the tenant
 * does not hold.
 *
 * <p>Hospital tenants only, enforced rather than asserted. The endpoint is CORE, and
 * {@code FacilityAccessAspect} deliberately waves CORE controllers through for every facility type,
 * so being CORE is what makes a clinic or pharmacy able to reach it. {@code @TenantType} is the
 * mechanism the ICU, OT and Recovery controllers already use to say "this facility type only", and
 * it is used here for the same reason: the blocks below describe wards, beds and inpatient
 * admissions, which a clinic or pharmacy does not have.
 */
@RestController
@RequestMapping("/hospital/dashboard")
@PreAuthorize("hasRole('HOSPITAL_ADMIN')")
@TenantType(HospitalType.HOSPITAL)
public class HospitalDashboardController {

    @Autowired private HospitalDashboardService dashboardService;

    /**
     * @param range TODAY, LAST_7_DAYS or LAST_30_DAYS; TODAY when omitted.
     *              The client sends the enum and nothing else — day boundaries are the server's
     *              to compute, in the hospital's business timezone, so that a browser in another
     *              timezone cannot shift what "today" means.
     */
    @GetMapping("/overview")
    public ResponseEntity<DashboardOverviewDTO> getOverview(
            @RequestParam(required = false) String range) {
        return ResponseEntity.ok(dashboardService.getOverview(DashboardRange.parse(range)));
    }
}

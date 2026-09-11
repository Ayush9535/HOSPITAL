package com.hms.security;

import com.hms.entity.Hospital;
import com.hms.entity.HospitalType;
import com.hms.repository.HospitalRepository;
import org.springframework.security.access.AccessDeniedException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The aspect must honour a CLASS-level @RequireModule (the old pointcut bound
 * @annotation only, silently disabling every class-level gate), and must skip
 * Clinic/Pharmacy sessions entirely -- three controllers they share with
 * Hospital are gated on modules their plan types cannot be granted.
 *
 * It must also read modules from the HOSPITAL ROW rather than the caller's JWT claim, so a plan
 * change by the Super Admin takes effect on the next request instead of at the next login.
 */
class ModuleAccessAspectTest {

    private final HospitalRepository hospitalRepository = mock(HospitalRepository.class);
    private final ModuleAccessAspect aspect = new ModuleAccessAspect();

    ModuleAccessAspectTest() {
        ReflectionTestUtils.setField(aspect, "hospitalRepository", hospitalRepository);
    }

    /** The tenant's live plan, as stored on the hospital row. */
    private void hospitalHasModules(List<String> modules) {
        Hospital hospital = new Hospital();
        hospital.setModules(modules == null ? null : new java.util.ArrayList<>(modules));
        when(hospitalRepository.findById(anyLong())).thenReturn(Optional.of(hospital));
    }

    /** Stands in for SurgeryController: gate declared on the class, not the method. */
    @RequireModule("OT")
    static class ClassGatedController {
        public void handler() {
        }
    }

    /** Stands in for a controller gating one method only. */
    static class MethodGatedController {
        @RequireModule("NURSING")
        public void handler() {
        }
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private JoinPoint joinPointFor(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(target);
        return joinPoint;
    }

    private void authenticate(Long hospitalId, String tenantType, List<String> modules) {
        UserAuthenticationDetails details = new UserAuthenticationDetails(1L, "DOCTOR", hospitalId, modules);
        details.setHospitalType(tenantType);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("u@x.com", null, List.of());
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void classLevelGate_isEnforced_whenHospitalLacksTheModule() throws Exception {
        authenticate(7L, HospitalType.HOSPITAL.name(), List.of("OPD", "IPD"));
        hospitalHasModules(List.of("OPD", "IPD"));
        JoinPoint jp = joinPointFor(new ClassGatedController(), "handler");

        assertThatThrownBy(() -> aspect.checkModuleAccess(jp))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("OT");
    }

    @Test
    void classLevelGate_passes_whenHospitalHasTheModule() throws Exception {
        authenticate(7L, HospitalType.HOSPITAL.name(), List.of("OPD", "OT"));
        hospitalHasModules(List.of("OPD", "OT"));
        JoinPoint jp = joinPointFor(new ClassGatedController(), "handler");

        assertThatCode(() -> aspect.checkModuleAccess(jp)).doesNotThrowAnyException();
    }

    @Test
    void methodLevelGate_stillEnforced() throws Exception {
        authenticate(7L, HospitalType.HOSPITAL.name(), List.of("OPD"));
        hospitalHasModules(List.of("OPD"));
        JoinPoint jp = joinPointFor(new MethodGatedController(), "handler");

        assertThatThrownBy(() -> aspect.checkModuleAccess(jp))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("NURSING");
    }

    /**
     * The Super Admin just ADDED a module to the tenant's plan. The user's JWT still carries the
     * old claim, but the request must be allowed immediately — not after they log out and back in.
     */
    @Test
    void moduleAddedToPlan_isHonoured_beforeTheUserLogsBackIn() throws Exception {
        authenticate(7L, HospitalType.HOSPITAL.name(), List.of("OPD"));  // stale token: no OT
        hospitalHasModules(List.of("OPD", "OT"));                        // live plan: OT bought
        JoinPoint jp = joinPointFor(new ClassGatedController(), "handler");

        assertThatCode(() -> aspect.checkModuleAccess(jp)).doesNotThrowAnyException();
    }

    /**
     * The mirror image: a module was REMOVED from the plan. The stale token still claims it, but
     * the tenant is no longer paying for it, so access must stop at once.
     */
    @Test
    void moduleRemovedFromPlan_isRevoked_beforeTheUserLogsBackIn() throws Exception {
        authenticate(7L, HospitalType.HOSPITAL.name(), List.of("OPD", "OT")); // stale token: has OT
        hospitalHasModules(List.of("OPD"));                                   // live plan: OT dropped
        JoinPoint jp = joinPointFor(new ClassGatedController(), "handler");

        assertThatThrownBy(() -> aspect.checkModuleAccess(jp))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("OT");
    }

    /** A vanished hospital row must not lock a live session out — fall back to the token claim. */
    @Test
    void missingHospitalRow_fallsBackToTheTokenClaim() throws Exception {
        authenticate(7L, HospitalType.HOSPITAL.name(), List.of("OPD", "OT"));
        when(hospitalRepository.findById(anyLong())).thenReturn(Optional.empty());
        JoinPoint jp = joinPointFor(new ClassGatedController(), "handler");

        assertThatCode(() -> aspect.checkModuleAccess(jp)).doesNotThrowAnyException();
    }

    /**
     * F16 regression: CLINIC cannot be granted HOSPITAL_INVENTORY, yet it shares
     * HospitalInventoryController. Enforcing here would 403 it permanently.
     */
    @Test
    void clinicSession_isNeverModuleGated() throws Exception {
        authenticate(7L, HospitalType.CLINIC.name(), List.of("OPD"));
        JoinPoint jp = joinPointFor(new ClassGatedController(), "handler");

        assertThatCode(() -> aspect.checkModuleAccess(jp)).doesNotThrowAnyException();
    }

    /** F16 regression: pharmacy plans hold only tier keys, never APPOINTMENTS/BILLING. */
    @Test
    void pharmacySession_isNeverModuleGated() throws Exception {
        authenticate(7L, HospitalType.PHARMACY.name(), List.of("SINGLE_PHARMACY"));
        JoinPoint jp = joinPointFor(new MethodGatedController(), "handler");

        assertThatCode(() -> aspect.checkModuleAccess(jp)).doesNotThrowAnyException();
    }

    @Test
    void superAdmin_bypassesTheGate() throws Exception {
        authenticate(null, null, null);
        JoinPoint jp = joinPointFor(new ClassGatedController(), "handler");

        assertThatCode(() -> aspect.checkModuleAccess(jp)).doesNotThrowAnyException();
    }

    /** A token minted before the hospitalType claim existed must not be revoked mid-session. */
    @Test
    void tokenWithoutTenantTypeClaim_isNotEnforced() throws Exception {
        authenticate(7L, null, List.of("OPD"));
        JoinPoint jp = joinPointFor(new ClassGatedController(), "handler");

        assertThatCode(() -> aspect.checkModuleAccess(jp)).doesNotThrowAnyException();
    }
}

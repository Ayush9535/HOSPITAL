package com.hms.security;

import com.hms.entity.HospitalType;
import org.springframework.security.access.AccessDeniedException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tenant isolation used to rest on a frontend axios prefix rewrite. A clinic's
 * DOCTOR holds ROLE_DOCTOR, which /hospital/** authorizes -- so only the browser
 * stopped them. These tests pin the server-side rule.
 */
class TenantTypeAspectTest {

    private final TenantTypeAspect aspect = new TenantTypeAspect();

    /** Stands in for SurgeryController / OtInchargeController. */
    @TenantType(HospitalType.HOSPITAL)
    static class HospitalOnlyController {
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

    private void authenticate(Long hospitalId, String tenantType) {
        UserAuthenticationDetails details = new UserAuthenticationDetails(1L, "DOCTOR", hospitalId, List.of());
        details.setHospitalType(tenantType);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("u@x.com", null, List.of());
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void hospitalSession_isAllowed() throws Exception {
        authenticate(7L, HospitalType.HOSPITAL.name());
        JoinPoint jp = joinPointFor(new HospitalOnlyController(), "handler");

        assertThatCode(() -> aspect.checkTenantType(jp)).doesNotThrowAnyException();
    }

    @Test
    void clinicSession_isRejected_onAHospitalOnlyController() throws Exception {
        authenticate(7L, HospitalType.CLINIC.name());
        JoinPoint jp = joinPointFor(new HospitalOnlyController(), "handler");

        assertThatThrownBy(() -> aspect.checkTenantType(jp)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void pharmacySession_isRejected_onAHospitalOnlyController() throws Exception {
        authenticate(7L, HospitalType.PHARMACY.name());
        JoinPoint jp = joinPointFor(new HospitalOnlyController(), "handler");

        assertThatThrownBy(() -> aspect.checkTenantType(jp)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void superAdmin_bypassesTheCheck() throws Exception {
        authenticate(null, null);
        JoinPoint jp = joinPointFor(new HospitalOnlyController(), "handler");

        assertThatCode(() -> aspect.checkTenantType(jp)).doesNotThrowAnyException();
    }

    /** A token minted before the claim existed cannot be classified; do not revoke it mid-session. */
    @Test
    void tokenWithoutTenantTypeClaim_isAllowed() throws Exception {
        authenticate(7L, null);
        JoinPoint jp = joinPointFor(new HospitalOnlyController(), "handler");

        assertThatCode(() -> aspect.checkTenantType(jp)).doesNotThrowAnyException();
    }
}

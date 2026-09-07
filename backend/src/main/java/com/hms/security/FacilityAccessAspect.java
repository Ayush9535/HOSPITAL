package com.hms.security;

import com.hms.entitlement.ControllerModules;
import com.hms.entitlement.EntitlementRegistry;
import com.hms.entity.Hospital;
import com.hms.entity.HospitalType;
import com.hms.repository.HospitalRepository;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Launch boundary for facility types. ControllerModules is the declared capability map; this
 * aspect enforces only facility eligibility, not full per-plan module entitlement.
 */
@Aspect
@Component
public class FacilityAccessAspect {

    @Autowired
    private HospitalRepository hospitalRepository;

    @Before("execution(public * com.hms.controller..*(..))")
    public void checkFacilityAccess(JoinPoint joinPoint) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof UserAuthenticationDetails details)
                || details.getHospitalId() == null) {
            return; // Platform/SUPER_ADMIN routes have no tenant.
        }

        Hospital hospital = hospitalRepository.findById(details.getHospitalId())
                .orElseThrow(() -> new AccessDeniedException("Access Denied: tenant is unavailable."));
        HospitalType type = hospital.getType() == null ? HospitalType.HOSPITAL : hospital.getType();
        if (type == HospitalType.HOSPITAL) {
            return;
        }

        String controller = ((MethodSignature) joinPoint.getSignature()).getDeclaringType().getSimpleName();
        String module = ControllerModules.moduleOf(controller);
        if (module == null || EntitlementRegistry.CORE.equals(module)) {
            return;
        }

        boolean allowed = EntitlementRegistry.isSellable(type, module);
        // Pharmacy branches are granted by the current pharmacy tier, not sold independently.
        if (!allowed && type == HospitalType.PHARMACY && EntitlementRegistry.PHARMACY_BRANCH.equals(module)) {
            allowed = EntitlementRegistry.resolve(hospital.getModules()).contains(module);
        }
        if (!allowed) {
            throw new AccessDeniedException("Access Denied: this feature is not available for your account type.");
        }
    }
}

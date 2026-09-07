package com.hms.security;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.hms.entity.Hospital;
import com.hms.entity.HospitalType;
import com.hms.repository.HospitalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * ModuleAccessAspect - enforces {@link RequireModule} on the current session.
 *
 * The pointcut matches BOTH placements (method and class). It cannot bind the
 * annotation as an advice parameter, because Spring AOP rejects a bound
 * parameter inside an `||` expression ("inconsistent binding"), so the
 * annotation is resolved from the JoinPoint: method first, then the class.
 *
 * Scope: HOSPITAL tenants only. Clinic and Pharmacy have never had module
 * enforcement, and three controllers they share with Hospital
 * (AppointmentController, HospitalFeeController, HospitalInventoryController)
 * are gated on modules their plan types cannot even be granted -- enforcing
 * here would permanently 403 them.
 *
 * @author HMS Team
 */
@Aspect
@Component
public class ModuleAccessAspect {

    @Autowired
    private HospitalRepository hospitalRepository;

    @Before("@annotation(com.hms.security.RequireModule) || @within(com.hms.security.RequireModule)")
    public void checkModuleAccess(JoinPoint joinPoint) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getDetails() instanceof UserAuthenticationDetails)) {
            return;
        }
        UserAuthenticationDetails details = (UserAuthenticationDetails) authentication.getDetails();

        // Super Admin has no hospital and bypasses module checks.
        if (details.getHospitalId() == null) {
            return;
        }

        // Only HOSPITAL tenants are module-gated (see class javadoc). A null type means
        // the token was minted before the claim existed: skip for one release rather
        // than revoke access from a live session mid-token.
        if (!HospitalType.HOSPITAL.name().equals(details.getHospitalType())) {
            return;
        }

        RequireModule requireModule = resolveAnnotation(joinPoint);
        if (requireModule == null) {
            return;
        }

        List<String> enabledModules = resolveEnabledModules(details);
        String requiredModule = requireModule.value();
        if (enabledModules == null || !enabledModules.contains(requiredModule)) {
            // 403, not 401: the session is valid, the plan simply lacks this module. A 401
            // makes the frontend interceptor clear the token and bounce the user to /login.
            throw new AccessDeniedException(
                    "Access Denied: Module '" + requiredModule + "' is not enabled for your hospital.");
        }
    }

    /**
     * The live module list for the caller's hospital — read from the hospital row, NOT from the
     * caller's JWT.
     *
     * The token's "modules" claim is a snapshot taken at login. When the Super Admin changes a
     * tenant's plan, every already-signed-in user of that tenant kept enforcing the old plan until
     * they logged out and back in: a module they just bought would 403, and one that was just
     * removed would still work. The hospital row is the live truth — the platform rewrites it on
     * every plan assignment and plan edit (PlatformPlanService.applyPlanToHospital) — so read it
     * here and a plan change takes effect on the very next request.
     *
     * Cheap: Hospital.modules is an EAGER collection and the row is served from Hibernate's cache
     * within a request. Falls back to the token claim only if the hospital row is gone, which
     * keeps a live session working rather than locking it out.
     */
    private List<String> resolveEnabledModules(UserAuthenticationDetails details) {
        return hospitalRepository.findById(details.getHospitalId())
                .map(Hospital::getModules)
                .orElseGet(details::getModules);
    }

    /** Method-level annotation wins over the class-level one. */
    private RequireModule resolveAnnotation(JoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RequireModule onMethod = AnnotatedElementUtils.findMergedAnnotation(method, RequireModule.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(joinPoint.getTarget().getClass(), RequireModule.class);
    }
}

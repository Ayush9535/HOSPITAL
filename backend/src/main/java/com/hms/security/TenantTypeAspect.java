package com.hms.security;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.hms.entity.HospitalType;
import org.springframework.security.access.AccessDeniedException;
import java.lang.reflect.Method;

/**
 * TenantTypeAspect - enforces {@link TenantType}.
 *
 * Matches both method- and class-level placement. Like ModuleAccessAspect it
 * resolves the annotation from the JoinPoint rather than binding it, because
 * Spring AOP cannot bind a parameter inside an `||` pointcut.
 *
 * @author HMS Team
 */
@Aspect
@Component
public class TenantTypeAspect {

    @Before("@annotation(com.hms.security.TenantType) || @within(com.hms.security.TenantType)")
    public void checkTenantType(JoinPoint joinPoint) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getDetails() instanceof UserAuthenticationDetails)) {
            return;
        }
        UserAuthenticationDetails details = (UserAuthenticationDetails) authentication.getDetails();

        // Super Admin has no tenant and bypasses the check.
        if (details.getHospitalId() == null) {
            return;
        }

        TenantType annotation = resolveAnnotation(joinPoint);
        if (annotation == null) {
            return;
        }

        // A token minted before the hospitalType claim existed cannot be classified.
        // Skip for one release rather than revoke a live session mid-token.
        String sessionType = details.getHospitalType();
        if (sessionType == null) {
            return;
        }

        for (HospitalType allowed : annotation.value()) {
            if (allowed.name().equals(sessionType)) {
                return;
            }
        }
        // 403, not 401: the session is valid, the tenant type simply isn't allowed here.
        // A 401 makes the frontend interceptor clear the token and bounce the user to /login.
        throw new AccessDeniedException("Access Denied: this feature is not available for your account type.");
    }

    /** Method-level annotation wins over the class-level one. */
    private TenantType resolveAnnotation(JoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        TenantType onMethod = AnnotatedElementUtils.findMergedAnnotation(method, TenantType.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(joinPoint.getTarget().getClass(), TenantType.class);
    }
}

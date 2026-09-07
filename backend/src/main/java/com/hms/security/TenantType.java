package com.hms.security;

import com.hms.entity.HospitalType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * TenantType - restricts a controller or handler to specific tenant types.
 *
 * Tenant isolation used to rest entirely on a frontend axios prefix rewrite
 * (/hospital/** -> /clinic/**) plus which controllers opt into a /clinic alias.
 * That is a convention, not an invariant: a clinic's DOCTOR user holds
 * ROLE_DOCTOR, which /hospital/** authorizes. This annotation makes the
 * restriction a server-side rule.
 *
 * Usage: {@code @TenantType(HospitalType.HOSPITAL)} on a hospital-only controller.
 *
 * @author HMS Team
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantType {
    /** Tenant types allowed to reach the annotated handler. */
    HospitalType[] value();
}

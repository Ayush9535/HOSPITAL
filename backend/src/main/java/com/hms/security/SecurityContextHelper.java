package com.hms.security;

import com.hms.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SecurityContextHelper - Helper class to extract user information from
 * security context
 * 
 * This class provides utility methods to extract:
 * - User ID
 * - Role
 * - Hospital ID
 * 
 * from the current Spring Security context.
 * Used by services to automatically filter data by hospital_id.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Component
public class SecurityContextHelper {

    /**
     * Get the current authenticated user's details
     * 
     * @return UserAuthenticationDetails containing userId, role, hospitalId
     * @throws RuntimeException if user is not authenticated
     */
    public UserAuthenticationDetails getCurrentUserDetails() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("User not authenticated");
        }

        Object details = authentication.getDetails();
        if (!(details instanceof UserAuthenticationDetails)) {
            throw new UnauthorizedException("Invalid authentication details");
        }

        return (UserAuthenticationDetails) details;
    }

    /**
     * Get the current user's hospital ID
     * 
     * @return Hospital ID (null for Super Admin)
     * @throws RuntimeException if user is not authenticated
     */
    public Long getCurrentHospitalId() {
        return getCurrentUserDetails().getHospitalId();
    }

    /**
     * Get the current user's Multi Pharmacy branch ID.
     *
     * @return branch ID for a branch login, or null (admins, single-shop pharmacists,
     *         hospital/clinic pharmacists) — meaning "not scoped to a branch".
     */
    public Long getCurrentBranchId() {
        try {
            return getCurrentUserDetails().getBranchId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the current user's ID
     * 
     * @return User ID
     * @throws RuntimeException if user is not authenticated
     */
    public Long getCurrentUserId() {
        return getCurrentUserDetails().getUserId();
    }

    /**
     * Get the current user's role
     * 
     * @return Role (SUPER_ADMIN, HOSPITAL_ADMIN, DOCTOR)
     * @throws RuntimeException if user is not authenticated
     */
    public String getCurrentUserRole() {
        return getCurrentUserDetails().getRole();
    }

    /**
     * Get the current user's email (username)
     * 
     * @return Email address
     * @throws RuntimeException if user is not authenticated
     */
    public String getCurrentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("User not authenticated");
        }
        return authentication.getName();
    }
}

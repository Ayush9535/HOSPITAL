package com.hms.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * UserAuthenticationDetails - Custom authentication details for storing user
 * context
 * 
 * This class stores additional user information in the Spring Security context:
 * - userId: User's unique identifier
 * - role: User's role (SUPER_ADMIN, HOSPITAL_ADMIN, DOCTOR)
 * - hospitalId: Hospital ID for multi-tenant isolation (null for Super Admin)
 * 
 * Services can access this information to automatically filter data by
 * hospitalId.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserAuthenticationDetails {

    /**
     * User's unique identifier
     */
    private Long userId;

    /**
     * User's role (SUPER_ADMIN, HOSPITAL_ADMIN, DOCTOR)
     */
    private String role;

    /**
     * Hospital ID for multi-tenant isolation
     * null for Super Admin users
     */
    /**
     * Hospital ID for multi-tenant isolation
     * null for Super Admin users
     */
    private Long hospitalId;

    /**
     * Enabled modules for this hospital session
     */
    private java.util.List<String> modules;

    /**
     * Multi Pharmacy branch ID for branch logins; null otherwise.
     */
    private Long branchId;

    /**
     * Tenant type of this session: HOSPITAL, CLINIC or PHARMACY.
     * Null for Super Admin, and null for tokens minted before the claim existed.
     * Treat null as "unknown" — never assume a type.
     */
    private String hospitalType;

    public UserAuthenticationDetails(Long userId, String role, Long hospitalId, java.util.List<String> modules) {
        this.userId = userId;
        this.role = role;
        this.hospitalId = hospitalId;
        this.modules = modules;
    }

    public String getHospitalType() {
        return hospitalType;
    }

    public void setHospitalType(String hospitalType) {
        this.hospitalType = hospitalType;
    }

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Long getHospitalId() {
        return hospitalId;
    }

    public void setHospitalId(Long hospitalId) {
        this.hospitalId = hospitalId;
    }

    public java.util.List<String> getModules() {
        return modules;
    }

    public void setModules(java.util.List<String> modules) {
        this.modules = modules;
    }
}

package com.hms.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * User - Entity representing all users in the system
 * 
 * This entity stores information for ALL users including:
 * - Super Admin (hospital_id = NULL, role = SUPER_ADMIN)
 * - Hospital Admin (hospital_id = valid ID, role = HOSPITAL_ADMIN)
 * - Doctor (hospital_id = valid ID, role = DOCTOR)
 * 
 * The hospital_id field is critical for multi-tenant isolation.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

    /**
     * Unique identifier for the user
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Public unique identifier (UUID) for security
     */
    /**
     * Public unique identifier (UUID) for security
     */
    @Column(nullable = false, unique = true)
    private String publicId;

    /**
     * Custom readable ID for UI display (e.g., USR1234)
     */
    @Column(name = "custom_id")
    private String customId;

    // Reused SecureRandom for the numeric suffix of custom IDs (a single shared, non-predictable
    // instance rather than a new java.util.Random() per call).
    private static final java.security.SecureRandom CUSTOM_ID_RANDOM = new java.security.SecureRandom();

    @PrePersist
    public void generateIds() {
        if (this.publicId == null) {
            this.publicId = java.util.UUID.randomUUID().toString();
        }
        // customId for RECEPTIONIST and NURSE is set by their service after save
        // (sequential, e.g. REC1 / NRS1). Other roles retain random generation for now.
        if (this.customId == null && !"RECEPTIONIST".equals(this.role) && !"NURSE".equals(this.role)) {
            String prefix = "USR";
            if ("HOSPITAL_ADMIN".equals(this.role))
                prefix = "ADM";
            else if ("DOCTOR".equals(this.role))
                prefix = "DOC";
            else if ("SUPER_ADMIN".equals(this.role))
                prefix = "SUP";
            this.customId = prefix + (1000 + CUSTOM_ID_RANDOM.nextInt(9000));
        }
    }

    /**
     * Email address used for login
     * Must be unique across the entire system
     */
    @Column(nullable = false, unique = true, length = 100)
    private String email;

    /**
     * Encrypted password.
     *
     * WRITE_ONLY: still bound from an inbound request body, but never serialised back out.
     * Several endpoints (e.g. POST /hospital/nurses) return the saved User entity directly,
     * which put the BCrypt hash in the response body.
     */
    @Column(nullable = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /**
     * User's full name
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * Role of the user: SUPER_ADMIN, HOSPITAL_ADMIN, or DOCTOR
     */
    @Column(nullable = false, length = 20)
    private String role;

    /**
     * Hospital ID for multi-tenant isolation
     * - NULL for Super Admin users
     * - Valid hospital ID for Hospital Admin and Doctor users
     * 
     * This field is used to filter all hospital-related data
     */
    @Column(name = "hospital_id")
    private Long hospitalId;

    /**
     * Branch ID for Multi Pharmacy tenants — the pharmacy_branch a PHARMACIST login
     * belongs to. NULL for all non-branch users (admins, single-shop pharmacists,
     * doctors, etc.).
     */
    @Column(name = "branch_id")
    private Long branchId;

    /**
     * Soft delete flag
     */
    @Column(nullable = false)
    private Boolean isActive = true;

    /**
     * Monotonic session generation. Every JWT carries the value current at login; the
     * authentication filter refuses a token whose value no longer matches, which is how a
     * credential or authority change ends sessions that are already in flight.
     *
     * <p>Bumped by {@link #invalidateSessionsOnCredentialChange()} — never by hand. See that
     * method for why.
     */
    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion = 0;

    /**
     * Timestamp when the user was created
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── session invalidation choke point ──────────────────────────────────────
    //
    // The password is reset from ten different services and the role is changed from two more.
    // Requiring each of them to remember to bump tokenVersion would work today and rot the first
    // time someone adds an eleventh path — and the failure is silent: the old session simply
    // keeps working, which is the exact thing this mechanism exists to prevent.
    //
    // So the bump is not a call any caller has to make. It is derived from what actually changed
    // on the row: JPA hands us the loaded values at @PostLoad, and at @PreUpdate we compare. Any
    // code path that changes a password hash or a role invalidates that user's sessions, whether
    // or not its author knew this mechanism existed.

    @Transient
    private String loadedPassword;

    @Transient
    private String loadedRole;

    /** True only for an instance that came from the database, so inserts are not treated as changes. */
    @Transient
    private boolean loadedFromDatabase;

    @PostLoad
    void captureCredentialSnapshot() {
        this.loadedPassword = this.password;
        this.loadedRole = this.role;
        this.loadedFromDatabase = true;
    }

    @PreUpdate
    void invalidateSessionsOnCredentialChange() {
        // A row that was never loaded is being inserted, or was built detached; there are no
        // outstanding tokens for it to invalidate and no snapshot to compare against.
        if (!loadedFromDatabase) {
            return;
        }
        boolean credentialChanged = !java.util.Objects.equals(loadedPassword, password)
                || !java.util.Objects.equals(loadedRole, role);
        if (credentialChanged) {
            this.tokenVersion = (this.tokenVersion == null ? 0 : this.tokenVersion) + 1;
            this.loadedPassword = this.password;
            this.loadedRole = this.role;
        }
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public String getCustomId() {
        return customId;
    }

    public void setCustomId(String customId) {
        this.customId = customId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

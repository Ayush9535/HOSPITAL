package com.hms.service.platform;

import com.hms.dto.CreateHospitalRequest;
import com.hms.entity.AuditLog;
import com.hms.entity.ClinicAdmin;
import com.hms.entity.Hospital;
import com.hms.entity.HospitalAdmin;
import com.hms.entity.HospitalType;
import com.hms.entity.PharmacyAdmin;
import com.hms.entity.User;
import com.hms.entity.Doctor;
import com.hms.entity.HospitalSetting;
import com.hms.exception.ResourceNotFoundException;
import com.hms.repository.AuditLogRepository;
import com.hms.repository.ClinicAdminRepository;
import com.hms.repository.HospitalAdminRepository;
import com.hms.repository.HospitalPlanSubscriptionRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.PharmacyAdminRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalSettingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PlatformHospitalService - Service for Super Admin hospital management
 * 
 * This service handles hospital-related operations for Super Admin:
 * - Creating new hospitals with hospital admin user
 * - Listing all hospitals
 * - Activating/deactivating hospitals
 * - Managing subscription plans
 * - System audit logging
 * 
 * Only Super Admin can access these operations.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Service
public class PlatformHospitalService {

    private static final Logger logger = LoggerFactory.getLogger(PlatformHospitalService.class);

    /**
     * Which OPD visits belong to a facility. Takes the hospital id three times.
     *
     * <p>opd has no hospital_id column: patient_id is the ownership rule the rest of the
     * application uses, while doctor_id and receptionist_id are swept as well because they
     * are foreign keys into doctors and users, which deleteHospital also removes.
     */
    private static final String OPD_TENANT_PREDICATE =
            "patient_id IN (SELECT id FROM patients WHERE hospital_id = ?)"
                    + " OR doctor_id IN (SELECT id FROM doctors WHERE hospital_id = ?)"
                    + " OR receptionist_id IN (SELECT id FROM users WHERE hospital_id = ?)";

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private com.hms.service.RealtimeNotifier notifier;

    @Autowired
    private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private HospitalAdminRepository hospitalAdminRepository;

    @Autowired
    private ClinicAdminRepository clinicAdminRepository;

    @Autowired
    private PharmacyAdminRepository pharmacyAdminRepository;

    @Autowired
    private HospitalSettingRepository hospitalSettingRepository;

    @Autowired
    private PlatformPlanService planService;

    @Autowired
    private HospitalPlanSubscriptionRepository subscriptionRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * Create a new hospital with hospital admin user
     * 
     * This method:
     * 1. Creates a new hospital record
     * 2. Creates a hospital admin user for that hospital
     * 3. Links the admin user to the hospital via hospital_id
     * 4. Logs the action
     * 
     * @param request CreateHospitalRequest containing hospital and admin details
     * @return Created Hospital entity
     * @throws RuntimeException if admin email already exists
     */
    @Transactional
    public Hospital createHospital(CreateHospitalRequest request) {
        if (userRepository.existsByEmail(request.getAdminEmail())) {
            throw new IllegalArgumentException("Email already exists: " + request.getAdminEmail());
        }

        HospitalType type = HospitalType.valueOf(request.getType());

        Hospital hospital = new Hospital();
        hospital.setName(request.getHospitalName());
        hospital.setIsActive(true);
        hospital.setType(type);
        if (type != HospitalType.PHARMACY && request.getIsSingleDoctor() != null) {
            hospital.setIsSingleDoctor(request.getIsSingleDoctor());
        } else {
            hospital.setIsSingleDoctor(false);
        }
        hospital = hospitalRepository.save(hospital);

        HospitalSetting settings = new HospitalSetting();
        settings.setHospital(hospital);
        settings.setReceptionMode("HAS_RECEPTIONIST");
        settings.setBillingHandler("RECEPTIONIST");
        settings.setInClinic(false);
        hospitalSettingRepository.save(settings);

        User admin = new User();
        admin.setEmail(request.getAdminEmail());
        admin.setPassword(passwordEncoder.encode(request.getAdminPassword()));
        admin.setName(request.getAdminName());
        admin.setRole("HOSPITAL_ADMIN");
        admin.setHospitalId(hospital.getId());
        userRepository.save(admin);

        if (type == HospitalType.CLINIC) {
            ClinicAdmin clinicAdmin = new ClinicAdmin();
            clinicAdmin.setHospitalId(hospital.getId());
            clinicAdmin.setName(request.getAdminName());
            clinicAdmin.setEmail(request.getAdminEmail());
            clinicAdmin.setPhone("");
            clinicAdmin.setIsActive(true);
            clinicAdminRepository.save(clinicAdmin);
        } else if (type == HospitalType.PHARMACY) {
            PharmacyAdmin pharmacyAdmin = new PharmacyAdmin();
            pharmacyAdmin.setHospitalId(hospital.getId());
            pharmacyAdmin.setName(request.getAdminName());
            pharmacyAdmin.setEmail(request.getAdminEmail());
            pharmacyAdmin.setPhone("");
            pharmacyAdmin.setIsActive(true);
            pharmacyAdminRepository.save(pharmacyAdmin);
        } else {
            HospitalAdmin hospitalAdmin = new HospitalAdmin();
            hospitalAdmin.setHospitalId(hospital.getId());
            hospitalAdmin.setName(request.getAdminName());
            hospitalAdmin.setEmail(request.getAdminEmail());
            hospitalAdmin.setPhone("");
            hospitalAdmin.setIsActive(true);
            hospitalAdminRepository.save(hospitalAdmin);
        }

        if (type != HospitalType.PHARMACY && Boolean.TRUE.equals(hospital.getIsSingleDoctor())) {
            Doctor doctor = new Doctor();
            doctor.setHospitalId(hospital.getId());
            doctor.setEmail(admin.getEmail());
            doctor.setName(admin.getName());
            doctor.setSpecialization("General Physician");
            doctor.setPhone("0000000055");
            doctor.setIsActive(true);
            doctor = doctorRepository.save(doctor);
            doctor.setCustomId("DOC" + doctor.getId());
            doctorRepository.save(doctor);
        }

        com.hms.dto.AssignPlanRequest assignReq = new com.hms.dto.AssignPlanRequest();
        assignReq.setHospitalPublicId(hospital.getPublicId());
        assignReq.setBillingPeriod(request.getBillingPeriod());
        com.hms.entity.HospitalPlanSubscription trialSub = planService.assignPlan(request.getPlanPublicId(), assignReq);
        // Apply 7-day free trial to all newly created entities
        trialSub.setExpiresAt(trialSub.getExpiresAt().plusDays(7));
        subscriptionRepository.save(trialSub);

        // In-Clinic must start OFF at creation for every tenant type. The plan grants
        // the In-Clinic *capability* (module), but assignPlan() may have enabled the
        // operational toggle — force it off so the admin explicitly opts in later.
        settings.setInClinic(false);
        // This is the new-tenant onboarding path. Existing settings retain their explicit
        // value; a new tenant that purchased NURSING starts with a usable nurse login flow.
        if (hospital.getModules() != null && hospital.getModules().contains("NURSING")) {
            settings.setSeparateNurseLogin(true);
        }
        hospitalSettingRepository.save(settings);

        logAction("HOSPITAL_CREATED",
            "Created " + type + ": " + hospital.getName() + " with admin: " + admin.getEmail());

        return hospitalRepository.findById(hospital.getId()).orElse(hospital);
    }

    /**
     * Get all hospitals ordered by creation date with pagination
     * 
     * @param pageable Pagination information
     * @return Page of hospitals
     */
    public org.springframework.data.domain.Page<Hospital> getAllHospitals(
            org.springframework.data.domain.Pageable pageable,
            String type) {
        org.springframework.data.domain.Page<Hospital> hospitals =
                (type != null && !type.isBlank())
                        ? hospitalRepository.findByTypeOrderByCreatedAtDesc(HospitalType.valueOf(type), pageable)
                        : hospitalRepository.findAllByOrderByCreatedAtDesc(pageable);

        // Enrich each row with its current plan name (transient field) so the
        // platform list shows the actual plan instead of a hardcoded default.
        hospitals.forEach(hospital ->
                subscriptionRepository.findByHospitalIdAndIsCurrentTrue(hospital.getId())
                        .ifPresent(sub -> hospital.setPlanName(sub.getPlan().getName())));

        return hospitals;
    }

    /**
     * Get tenant statistics for Super Admin Overview dashboard, broken down
     * by business type (hospitals, clinics, pharmacies) since all three are
     * stored as HospitalType-discriminated rows in the same table.
     *
     * @return Map keyed by "hospitals"/"clinics"/"pharmacies", each holding
     *         its own total/active/inactive counts
     */
    public Map<String, Map<String, Long>> getHospitalStats() {
        Map<String, Map<String, Long>> stats = new HashMap<>();
        stats.put("hospitals", getStatsByType(HospitalType.HOSPITAL));
        stats.put("clinics", getStatsByType(HospitalType.CLINIC));
        stats.put("pharmacies", getStatsByType(HospitalType.PHARMACY));
        return stats;
    }

    private Map<String, Long> getStatsByType(HospitalType type) {
        long total = hospitalRepository.countByType(type);
        long active = hospitalRepository.countByTypeAndIsActive(type, true);
        long inactive = total - active;

        Map<String, Long> typeStats = new HashMap<>();
        typeStats.put("total", total);
        typeStats.put("active", active);
        typeStats.put("inactive", inactive);
        return typeStats;
    }

    /**
     * Get a hospital by Public ID
     * 
     * @param publicId Hospital Public ID
     * @return Hospital entity
     * @throws RuntimeException if hospital not found
     */
    public Hospital getHospitalByPublicId(String publicId) {
        if (publicId == null) {
            throw new IllegalArgumentException("Hospital ID cannot be null");
        }
        return hospitalRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + publicId));
    }

    /**
     * Get hospital details with admin email
     */
    public com.hms.dto.HospitalDetailsDTO getHospitalDetails(String publicId) {
        Hospital hospital = getHospitalByPublicId(publicId);

        com.hms.dto.HospitalDetailsDTO dto = new com.hms.dto.HospitalDetailsDTO();
        dto.setPublicId(hospital.getPublicId());
        dto.setCustomId(hospital.getCustomId());
        dto.setName(hospital.getName());
        dto.setIsActive(hospital.getIsActive());
        // dto.setPlan(hospital.getPlan()); // Removed - plan field replaced with subscription info (Task 12)
        dto.setModules(hospital.getModules());
        dto.setAddress(hospital.getAddress());
        dto.setPhone(hospital.getPhone());
        dto.setIsSingleDoctor(hospital.getIsSingleDoctor());
        dto.setType(hospital.getType() != null ? hospital.getType().name() : "HOSPITAL");
        dto.setSubscriptionStatus(hospital.getSubscriptionStatus());

        subscriptionRepository.findByHospitalIdAndIsCurrentTrue(hospital.getId()).ifPresent(sub -> {
            dto.setPlanName(sub.getPlan().getName());
            dto.setBillingPeriod(sub.getBillingPeriod().name());
            dto.setAssignedAt(sub.getAssignedAt());
            dto.setExpiresAt(sub.getExpiresAt());
        });

        // Fetch Admin Email
        List<User> admins = userRepository.findByHospitalIdAndRole(hospital.getId(), "HOSPITAL_ADMIN");
        if (!admins.isEmpty()) {
            dto.setAdminEmail(admins.get(0).getEmail());
            dto.setAdminName(admins.get(0).getName());
        }

        return dto;
    }

    /**
     * Activate or deactivate a hospital
     * 
     * When a hospital is deactivated, its users cannot log in or access the system.
     * 
     * @param publicId Hospital Public ID
     * @param isActive New active status
     * @param reason   Reason for status change
     * @return Updated Hospital entity
     * @throws RuntimeException if hospital not found
     */
    public Hospital updateHospitalStatus(String publicId, Boolean isActive, String reason) {
        Hospital hospital = hospitalRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + publicId));

        boolean oldStatus = hospital.getIsActive();
        hospital.setIsActive(isActive);
        Hospital savedHospital = hospitalRepository.save(hospital);

        if (oldStatus != isActive) {
            String action = isActive ? "HOSPITAL_ACTIVATED" : "HOSPITAL_BLOCKED";
            logAction(action,
                    "Updated status for hospital: " + hospital.getName() + " to " + (isActive ? "Active" : "Inactive"),
                    reason);
            // Blocking a tenant has to reach the people using it right now, not at their next
            // login. SETTINGS_UPDATED makes each client re-read /auth/me, which refuses an
            // inactive hospital (401) and drops them at the login screen — so a blocked tenant
            // stops working immediately instead of staying usable for the rest of the session.
            notifyTenant(hospital.getId());
        }

        return savedHospital;
    }

    /**
     * Tell everyone signed in at a tenant that something the platform owns changed underneath
     * them (name, admin identity, single-doctor mode, active status), so the UI reflects it live
     * instead of at the next login. SETTINGS_UPDATED re-reads the profile; REFRESH_DATA reloads
     * the lists. Best-effort: a socket failure must not fail the platform's write.
     *
     * Fires after the transaction commits where one is active, so a client that immediately
     * re-fetches cannot read the pre-change row and cache it again.
     */
    private void notifyTenant(Long hospitalId) {
        if (hospitalId == null) return;
        Runnable push = () -> {
            try {
                webSocketHandler.broadcast(hospitalId, "{\"type\":\"SETTINGS_UPDATED\"}");
                webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
            } catch (Exception e) {
                logger.warn("Failed to broadcast platform change to hospital {}", hospitalId, e);
            }
        };

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            push.run();
                        }
                    });
        } else {
            push.run();
        }
    }

    /**
     * Reset Tenant Admin Password
     * Password is now provided by Super Admin — not auto-generated.
     *
     * @param publicId    Hospital Public ID
     * @param newPassword New password chosen by Super Admin
     * @param reason      Reason for reset
     * @return Map containing "email" and success confirmation
     */
    public Map<String, String> resetTenantAdminPassword(String publicId, String newPassword, String reason) {
        if (newPassword == null || newPassword.trim().isEmpty()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }
        if (newPassword.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }

        Hospital hospital = hospitalRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + publicId));

        List<User> admins = userRepository.findByHospitalIdAndRole(hospital.getId(), "HOSPITAL_ADMIN");
        if (admins.isEmpty()) {
            throw new ResourceNotFoundException("No admin found for this hospital");
        }

        User admin = admins.get(0);
        admin.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(admin);

        logAction("PASSWORD_RESET", "Reset password for hospital admin: " + admin.getEmail(), reason);

        Map<String, String> result = new HashMap<>();
        result.put("email", admin.getEmail());
        result.put("message", "Password reset successfully");
        return result;
    }

    /**
     * Update hospital name, admin email, and single doctor status
     */
    @Transactional
    public Hospital updateHospitalDetails(String publicId, String name, String adminEmail, String adminName,
            String reason, Boolean isSingleDoctor) {
        Hospital hospital = hospitalRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + publicId));

        boolean nameChanged = !hospital.getName().equals(name);
        String oldName = hospital.getName();
        hospital.setName(name);

        boolean isSingleDoctorChanged = isSingleDoctor != null && !isSingleDoctor.equals(hospital.getIsSingleDoctor());
        if (isSingleDoctor != null) {
            hospital.setIsSingleDoctor(isSingleDoctor);
        }

        // Update Admin Email & Name
        List<User> admins = userRepository.findByHospitalIdAndRole(hospital.getId(), "HOSPITAL_ADMIN");
        boolean emailChanged = false;
        boolean adminNameChanged = false;
        String oldEmail = "";
        String oldAdminName = "";
        String adminEmailValue = adminEmail;

        if (!admins.isEmpty()) {
            User admin = admins.get(0);

            // Update Email
            if (adminEmail != null && !adminEmail.trim().isEmpty() && !admin.getEmail().equals(adminEmail)) {
                if (userRepository.existsByEmail(adminEmail)) {
                    throw new IllegalArgumentException("Email already exists: " + adminEmail);
                }
                oldEmail = admin.getEmail();
                admin.setEmail(adminEmail);
                emailChanged = true;
            } else {
                adminEmailValue = admin.getEmail(); // Fallback if not changed
            }

            // Update Name
            if (adminName != null && !adminName.trim().isEmpty() && !admin.getName().equals(adminName)) {
                oldAdminName = admin.getName();
                admin.setName(adminName);
                adminNameChanged = true;
            }

            if (emailChanged || adminNameChanged) {
                userRepository.save(admin);
            }
        }

        Hospital savedHospital = hospitalRepository.save(hospital);

        // If Single Doctor Clinic is enabled, ensure a Doctor profile exists (only if OPD is enabled)
        if (Boolean.TRUE.equals(savedHospital.getIsSingleDoctor()) && savedHospital.getModules() != null && savedHospital.getModules().contains("OPD")) {
            String targetEmail = adminEmail != null ? adminEmail.trim() : (admins.isEmpty() ? "" : admins.get(0).getEmail());
            String targetName = adminName != null ? adminName.trim() : (admins.isEmpty() ? "" : admins.get(0).getName());
            
            if (!targetEmail.isEmpty()) {
                boolean doctorExists = doctorRepository.findByEmailAndHospitalId(targetEmail, savedHospital.getId()).isPresent();
                if (!doctorExists) {
                    Doctor doctor = new Doctor();
                    doctor.setHospitalId(savedHospital.getId());
                    doctor.setEmail(targetEmail);
                    doctor.setName(targetName);
                    doctor.setSpecialization("General Physician");
                    doctor.setPhone("0000000055");
                    doctor.setIsActive(true);
                    doctor = doctorRepository.save(doctor);
                    doctor.setCustomId("DOC" + doctor.getId());
                    doctorRepository.save(doctor);
                }
            }
        }

        if (nameChanged || emailChanged || adminNameChanged || isSingleDoctorChanged) {
            StringBuilder details = new StringBuilder("Updated details for hospital. ");
            if (nameChanged)
                details.append("Hospital Name: '").append(oldName).append("' -> '").append(name).append("'. ");
            if (emailChanged)
                details.append("Email: '").append(oldEmail).append("' -> '").append(adminEmailValue).append("'. ");
            if (adminNameChanged)
                details.append("Admin Name: '").append(oldAdminName).append("' -> '").append(adminName).append("'. ");
            if (isSingleDoctorChanged)
                details.append("Single Doctor Mode: '").append(!isSingleDoctor).append("' -> '").append(isSingleDoctor).append("'.");
            logAction("HOSPITAL_UPDATED", details.toString(), reason);
            // The tenant's own header shows the hospital name, and single-doctor mode changes what
            // the admin can do — push it rather than making them log out to see it.
            notifyTenant(savedHospital.getId());
        }

        return savedHospital;
    }

    /**
     * Delete a facility (hospital, clinic or pharmacy) and all its related data.
     *
     * <p>Statements are ordered child-before-parent so every foreign key stays satisfied
     * while it runs. FOREIGN_KEY_CHECKS is deliberately NOT disabled: suppressing the
     * constraints also suppresses the ON DELETE CASCADE rules that the migration-created
     * tenant tables declare against hospitals(id), which is what left orphaned rows behind.
     * Leaving the checks on means the database enforces the ordering rather than trusting it,
     * and the cascades it owns actually fire.
     *
     * <p>OPD carries no hospital_id. Its tenancy is the owning patient -- the same rule
     * OpdRepository uses ("Opd has no hospital_id of its own, so tenancy is proven through
     * the owning patient"). Deletion additionally sweeps rows reachable through this
     * facility's doctor or receptionist: those columns are real foreign keys into doctors
     * and users, both of which are deleted below, so a visit with a missing patient would
     * otherwise survive and block them.
     */
    @Transactional
    public void deleteHospital(String publicId) {
        Hospital hospital = hospitalRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + publicId));
        Long id = hospital.getId();
        String name = hospital.getName();

        purgeTenantConfiguration(id);

        // Billing
        jdbcTemplate.update("DELETE FROM billing_payments WHERE billing_id IN (SELECT id FROM billing WHERE hospital_id = ?)", id);
        jdbcTemplate.update("DELETE FROM billing_medicines WHERE billing_id IN (SELECT id FROM billing WHERE hospital_id = ?)", id);
        jdbcTemplate.update("DELETE FROM billing_items WHERE billing_id IN (SELECT id FROM billing WHERE hospital_id = ?)", id);
        jdbcTemplate.update("DELETE FROM billing WHERE hospital_id = ?", id);

        // IPD
        jdbcTemplate.update("DELETE FROM discharge_summary WHERE ipd_admission_id IN (SELECT id FROM ipd_admission WHERE hospital_id = ?)", id);
        jdbcTemplate.update("DELETE FROM ipd_bed_history WHERE ipd_admission_id IN (SELECT id FROM ipd_admission WHERE hospital_id = ?)", id);
        jdbcTemplate.update("DELETE FROM ipd_admission WHERE hospital_id = ?", id);

        // OPD -- scoped through patient / doctor / receptionist, since opd has no hospital_id.
        jdbcTemplate.update(
                "DELETE FROM queue_entry WHERE opd_id IN (SELECT id FROM opd WHERE " + OPD_TENANT_PREDICATE + ")"
                        + " OR doctor_id IN (SELECT id FROM doctors WHERE hospital_id = ?)",
                id, id, id, id);
        jdbcTemplate.update("DELETE FROM opd WHERE " + OPD_TENANT_PREDICATE, id, id, id);

        // Clinical records
        jdbcTemplate.update("DELETE FROM prescriptions WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM medical_records WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM lab_orders WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM appointments WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM patients WHERE hospital_id = ?", id);

        // Pharmacy
        jdbcTemplate.update("DELETE FROM pharmacy_sale_items WHERE sale_id IN (SELECT id FROM pharmacy_sales WHERE hospital_id = ?)", id);
        jdbcTemplate.update("DELETE FROM pharmacy_sales WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM purchase_invoice_items WHERE purchase_invoice_id IN (SELECT id FROM purchase_invoices WHERE hospital_id = ?)", id);
        jdbcTemplate.update("DELETE FROM purchase_invoices WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM inventory_transactions WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM medicine_batches WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM medicine_master WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM suppliers WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM manufacturers WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM medicine_categories WHERE hospital_id = ?", id);

        // Hospital inventory
        jdbcTemplate.update("DELETE FROM hospital_inventory_purchase WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM hospital_inventory WHERE hospital_id = ?", id);

        // Clinic medicines
        jdbcTemplate.update("DELETE FROM medicine_purchase WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM medicines WHERE hospital_id = ?", id);
        // medicine_list is NOT deleted: it is the global medicine catalogue the platform
        // administers, discriminated by hospital_type (HOSPITAL/CLINIC/PHARMACY), not owned
        // by a facility. Its hospital_id column was dropped by DatabaseMigrationRunner when
        // the table became shared master data; deleting from it here would have destroyed
        // the catalogue for every other tenant.

        // Inventory items
        jdbcTemplate.update("DELETE FROM inventory_items WHERE hospital_id = ?", id);

        // Beds & wards -- beds carry their own hospital_id, so no join through wards is needed
        // (and wards' primary key is ward_id, not id, which the previous subquery assumed).
        jdbcTemplate.update("DELETE FROM beds WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM wards WHERE hospital_id = ?", id);

        // Staff
        jdbcTemplate.update("DELETE FROM doctors WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM receptionists WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM pharmacists WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM hospital_admins WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM clinic_admins WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM pharmacy_admins WHERE hospital_id = ?", id);

        // Settings & fees
        jdbcTemplate.update("DELETE FROM hospital_fees WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM hospital_settings WHERE hospital_id = ?", id);

        // Subscriptions & modules
        jdbcTemplate.update("DELETE FROM hospital_plan_subscriptions WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM hospital_modules WHERE hospital_id = ?", id);

        // Users & audit data
        jdbcTemplate.update("DELETE FROM users WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM audit_logs WHERE hospital_id = ?", id);
        jdbcTemplate.update("DELETE FROM support_tickets WHERE hospital_id = ?", id);

        // The facility row last: tenant tables created by the migration runner declare
        // ON DELETE CASCADE against it, so this is what collects them.
        jdbcTemplate.update("DELETE FROM hospitals WHERE id = ?", id);

        logAction("HOSPITAL_DELETED", "Permanently deleted hospital: " + name + " (publicId: " + publicId + ")");
    }

    /**
     * Removes the facility's configuration, presets, scheduling and permissions.
     *
     * <p>These tables carried a hospital_id and were never in the delete path, so every deleted
     * facility left them behind. The statements are generated from {@link TenantTables}, in its
     * declared child-before-parent order, rather than written out here: the registry is what the
     * fence test checks against, and a list that lives in two places drifts.
     *
     * <p>Runs before anything else, because role_permissions is first in that order. If a facility
     * id is ever reused -- the AUTO_INCREMENT counter resets on TRUNCATE, on an environment
     * rebuild, and on restart before MySQL 8.0 -- a surviving permission matrix would be picked up
     * by whoever inherits the id, and OtPermissionService reads "no rows" as "not configured",
     * so it would not fall back to the safe defaults.
     *
     * <p>Only PURGE_SAFE tables. Clinical, financial, HR and audit history is untouched by this
     * method; what the rest of deleteHospital does with those is unchanged.
     *
     * <p>Table names and predicates are compile-time constants from the registry, never request
     * input, so string-building the statement introduces no injection surface. The hospital id is
     * still bound as a parameter.
     */
    private void purgeTenantConfiguration(Long hospitalId) {
        for (TenantTables.TenantTable table : TenantTables.purgeSafeInOrder()) {
            Object[] args = new Object[table.binds()];
            java.util.Arrays.fill(args, hospitalId);
            jdbcTemplate.update("DELETE FROM " + table.table() + " WHERE " + table.where(), args);
        }
    }

    /**
     * Get all audit logs
     *
     * @return List of audit logs
     */
    public List<AuditLog> getAuditLogs() {
        // Platform login shows only platform-level activity (tenant onboarding/updates/blocks,
        // plan changes, password resets) — i.e. Super Admin actions, which carry a null hospitalId.
        // Each tenant's own internal events (hospitalId set) stay in that tenant's audit view.
        return auditLogRepository.findByHospitalIdIsNullOrderByTimestampDesc();
    }

    /**
     * Helper to log actions
     */
    private void logAction(String action, String details, String reason) {
        logAction(action, details, reason, null);
    }

    private void logAction(String action, String details) {
        logAction(action, details, null, null);
    }

    private void logAction(String action, String details, String reason, Long hospitalId) {
        try {
            String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
            AuditLog log = new AuditLog();
            log.setAction(action);
            log.setDetails(details);
            log.setPerformedBy(currentUsername);
            log.setReason(reason);
            log.setHospitalId(hospitalId);
            auditLogRepository.save(log);
        } catch (Exception e) {
            // Improve: Log error using SLF4J, but don't fail the operation
            logger.error("Failed to save audit log: {}", e.getMessage(), e);
        }

        // Every tenant mutation the platform performs (create, block, password reset, update,
        // delete) passes through here, so this is the one place that can keep a second Super Admin
        // tab honest. Without it the platform's own tenant list was the most stale screen in the
        // product: it broadcast nothing and listened for nothing but NEW_TICKET.
        notifier.platform("{\"type\":\"REFRESH_DATA\"}");
    }
}

package com.hms.service.hospital;

import com.hms.exception.ForbiddenException;

import com.hms.dto.LoginRequest;
import com.hms.dto.LoginResponse;
import com.hms.dto.ProfileUpdateRequest;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.entity.ClinicAdmin;
import com.hms.entity.Hospital;
import com.hms.entity.HospitalSetting;
import com.hms.entity.HospitalType;
import com.hms.entity.PharmacyAdmin;
import com.hms.entity.User;
import com.hms.entity.HospitalAdmin;
import com.hms.entity.Receptionist;
import com.hms.entity.Pharmacist;
import com.hms.entity.Doctor;
import com.hms.repository.ClinicAdminRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.HospitalSettingRepository;
import com.hms.repository.PharmacyAdminRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.HospitalAdminRepository;
import com.hms.repository.ReceptionistProfileRepository;
import com.hms.repository.PharmacistProfileRepository;
import com.hms.repository.DoctorRepository;
import com.hms.dto.HospitalSettingDTO;
import com.hms.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hms.util.LogSanitizer;

/**
 * HospitalAuthService - Authentication service for Hospital users
 * 
 * This service handles login for Hospital users:
 * - Hospital Admin (role = HOSPITAL_ADMIN)
 * - Doctor (role = DOCTOR)
 * 
 * All hospital users must have a valid hospital_id.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Service
public class HospitalAuthService {

    private static final Logger logger = LoggerFactory.getLogger(HospitalAuthService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private HospitalSettingRepository hospitalSettingRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    @Autowired
    private HospitalAdminRepository hospitalAdminRepository;

    @Autowired
    private ClinicAdminRepository clinicAdminRepository;

    @Autowired
    private PharmacyAdminRepository pharmacyAdminRepository;

    @Autowired
    private ReceptionistProfileRepository receptionistProfileRepository;

    @Autowired
    private PharmacistProfileRepository pharmacistProfileRepository;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private com.hms.service.hospital.ot.OtPermissionService otPermissionService;

    /**
     * Helper to populate detailed profile fields on LoginResponse.
     * Routes HOSPITAL_ADMIN to the correct admin table based on hospital type.
     */
    private void populateProfileDetails(User user, LoginResponse response, Hospital hospital) {
        if ("SUPER_ADMIN".equals(user.getRole())) {
            return;
        }
        if ("HOSPITAL_ADMIN".equals(user.getRole())) {
            HospitalType entityType = hospital != null && hospital.getType() != null
                    ? hospital.getType() : HospitalType.HOSPITAL;

            String phone = "";
            Integer age = null;
            String gender = null;

            if (entityType == HospitalType.CLINIC) {
                ClinicAdmin admin = clinicAdminRepository.findByEmail(user.getEmail())
                        .orElseGet(() -> {
                            ClinicAdmin a = new ClinicAdmin();
                            a.setHospitalId(user.getHospitalId());
                            a.setName(user.getName());
                            a.setEmail(user.getEmail());
                            a.setPhone("");
                            a.setIsActive(true);
                            return clinicAdminRepository.save(a);
                        });
                phone = admin.getPhone();
                age = admin.getAge();
                gender = admin.getGender();
            } else if (entityType == HospitalType.PHARMACY) {
                PharmacyAdmin admin = pharmacyAdminRepository.findByEmail(user.getEmail())
                        .orElseGet(() -> {
                            PharmacyAdmin a = new PharmacyAdmin();
                            a.setHospitalId(user.getHospitalId());
                            a.setName(user.getName());
                            a.setEmail(user.getEmail());
                            a.setPhone("");
                            a.setIsActive(true);
                            return pharmacyAdminRepository.save(a);
                        });
                phone = admin.getPhone();
                age = admin.getAge();
                gender = admin.getGender();
            } else {
                HospitalAdmin admin = hospitalAdminRepository.findByEmail(user.getEmail())
                        .orElseGet(() -> {
                            HospitalAdmin a = new HospitalAdmin();
                            a.setHospitalId(user.getHospitalId());
                            a.setName(user.getName());
                            a.setEmail(user.getEmail());
                            a.setPhone("");
                            a.setIsActive(true);
                            return hospitalAdminRepository.save(a);
                        });
                phone = admin.getPhone();
                age = admin.getAge();
                gender = admin.getGender();
            }

            response.setPhone(phone);
            response.setAge(age);
            response.setGender(gender);
            if (Boolean.TRUE.equals(response.getIsSingleDoctor())) {
                doctorRepository.findByEmailAndHospitalId(user.getEmail(), user.getHospitalId())
                        .ifPresent(doc -> response.setSpecialization(doc.getSpecialization()));
            } else {
                response.setSpecialization(null);
            }
        } else if ("RECEPTIONIST".equals(user.getRole())) {
            Receptionist receptionist = receptionistProfileRepository.findByEmail(user.getEmail())
                    .orElseGet(() -> {
                        Receptionist newRec = new Receptionist();
                        newRec.setHospitalId(user.getHospitalId());
                        newRec.setName(user.getName());
                        newRec.setEmail(user.getEmail());
                        newRec.setPhone("");
                        newRec.setAge(null);
                        newRec.setGender(null);
                        newRec.setIsActive(true);
                        return receptionistProfileRepository.save(newRec);
                    });
            response.setPhone(receptionist.getPhone());
            response.setAge(receptionist.getAge());
            response.setGender(receptionist.getGender());
            response.setSpecialization(null);
        } else if ("PHARMACIST".equals(user.getRole())) {
            Pharmacist pharmacist = pharmacistProfileRepository.findByEmail(user.getEmail())
                    .orElseGet(() -> {
                        Pharmacist newPhar = new Pharmacist();
                        newPhar.setHospitalId(user.getHospitalId());
                        newPhar.setName(user.getName());
                        newPhar.setEmail(user.getEmail());
                        newPhar.setPhone("");
                        newPhar.setAge(null);
                        newPhar.setGender(null);
                        newPhar.setIsActive(true);
                        return pharmacistProfileRepository.save(newPhar);
                    });
            response.setPhone(pharmacist.getPhone());
            response.setAge(pharmacist.getAge());
            response.setGender(pharmacist.getGender());
            response.setSpecialization(null);
        } else if ("DOCTOR".equals(user.getRole())) {
            Doctor doctor = doctorRepository.findByEmailAndHospitalId(user.getEmail(), user.getHospitalId())
                    .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
            response.setPhone(doctor.getPhone());
            response.setSpecialization(doctor.getSpecialization());
            response.setAge(null);
            response.setGender(null);
        }
    }

    /**
     * Authenticate Hospital user and generate JWT token
     * 
     * @param request LoginRequest containing email and password
     * @return LoginResponse with JWT token and user details
     * @throws RuntimeException if credentials are invalid, user is not a hospital
     *                          user, or hospital is inactive
     */
    public LoginResponse login(LoginRequest request) {
        logger.info("Hospital login attempt for email: {}", LogSanitizer.clean(request.getEmail()));

        // Find user by email — same message as wrong password to prevent user enumeration
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    logger.warn("Login failed - user not found: {}", LogSanitizer.clean(request.getEmail()));
                    return new UnauthorizedException("Invalid credentials");
                });

        // Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            logger.warn("Login failed - invalid password for user: {}", LogSanitizer.clean(request.getEmail()));
            throw new UnauthorizedException("Invalid credentials");
        }

        logger.debug("Password verified for user: {}", LogSanitizer.clean(request.getEmail()));

        // Verify user is a hospital user (not Super Admin)
        if ("SUPER_ADMIN".equals(user.getRole())) {
            logger.warn("Login failed - Super Admin tried to login via hospital endpoint: {}", LogSanitizer.clean(request.getEmail()));
            throw new ForbiddenException("Access denied. Please use platform login.");
        }

        // Verify user has a hospital_id
        if (user.getHospitalId() == null) {
            logger.error("Login failed - hospital user has null hospital_id: {}", LogSanitizer.clean(request.getEmail()));
            throw new UnauthorizedException("Invalid hospital user account");
        }

        logger.debug("User role and hospital_id validated for: {}", LogSanitizer.clean(request.getEmail()));

        // Verify hospital exists and is active
        Hospital hospital = hospitalRepository.findById(user.getHospitalId())
                .orElseThrow(() -> {
                    logger.error("Login failed - hospital not found for ID: {}", user.getHospitalId());
                    return new ResourceNotFoundException("Hospital not found");
                });

        if (hospital.getIsActive() == null || !hospital.getIsActive()) {
            logger.warn("Login failed - hospital is inactive: {} (ID: {})", hospital.getName(), hospital.getId());
            throw new UnauthorizedException("Hospital is inactive. Please contact support.");
        }

        // Validate that the user is logging into the correct portal
        if (request.getEntityType() != null && !request.getEntityType().isBlank()) {
            HospitalType expectedType;
            try {
                expectedType = HospitalType.valueOf(request.getEntityType().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new UnauthorizedException("Invalid entity type: " + request.getEntityType());
            }
            HospitalType actualType = hospital.getType() != null ? hospital.getType() : HospitalType.HOSPITAL;
            if (actualType != expectedType) {
                String portalName = expectedType == HospitalType.CLINIC ? "Clinic" :
                                    expectedType == HospitalType.PHARMACY ? "Pharmacy" : "Hospital";
                logger.warn("Login failed - wrong portal: user belongs to {} but tried {} portal: {}",
                        actualType, expectedType, LogSanitizer.clean(request.getEmail()));
                // Do not reveal that the account exists under a different tenant type — that leaks
                // account existence/type. Surface the same generic message as a bad password.
                throw new UnauthorizedException("Invalid credentials");
            }
        }

        // Fetch hospital settings (or auto-create default row if not exist)
        HospitalSetting settings = hospitalSettingRepository.findByHospital_Id(hospital.getId())
                .orElseGet(() -> {
                    HospitalSetting newSettings = new HospitalSetting();
                    newSettings.setHospital(hospital);
                    newSettings.setReceptionMode("HAS_RECEPTIONIST");
                    newSettings.setBillingHandler("RECEPTIONIST");
                    newSettings.setInClinic(Boolean.TRUE);
                    return hospitalSettingRepository.save(newSettings);
                });

        // Restrict receptionist login if Solo Doctor mode is active
        if ("RECEPTIONIST".equals(user.getRole()) && "SOLO".equals(settings.getReceptionMode())) {
            logger.warn("Login failed - Receptionist login restricted under Solo Doctor mode: {}", LogSanitizer.clean(request.getEmail()));
            throw new ForbiddenException("Solo Doctor Mode is active. Receptionist login is restricted.");
        }

        // Staff-nurse login requires Separate Nurse Login to be ON. A Nurse Incharge
        // (NURSE_INCHARGE) can always log in; a staff NURSE only when the setting is on.
        if ("NURSE".equals(user.getRole()) && !Boolean.TRUE.equals(settings.getSeparateNurseLogin())) {
            logger.warn("Login failed - staff nurse login disabled (Separate Nurse Login off): {}", LogSanitizer.clean(request.getEmail()));
            throw new ForbiddenException("Nurse login is disabled for this hospital. Please contact your Nurse Incharge or administrator.");
        }

        // Verify user account is active (handle null as active for backward compatibility)
        if (user.getIsActive() != null && !user.getIsActive()) {
            logger.warn("Login failed - user account is inactive: {}", LogSanitizer.clean(request.getEmail()));
            throw new UnauthorizedException("User account is inactive. Please contact administrator.");
        }

        logger.info("Login successful for user: {} at hospital: {}", LogSanitizer.clean(request.getEmail()), hospital.getName());

        // Generate JWT token with hospital_id, modules, tenant type and OT permissions
        HospitalType tenantType = hospital.getType() != null ? hospital.getType() : HospitalType.HOSPITAL;
        // OT is hospital-only: a clinic or pharmacy token never carries a permission.
        java.util.Collection<String> permissions = tenantType == HospitalType.HOSPITAL
                ? otPermissionService.effectiveFor(user.getHospitalId(), user.getRole())
                : java.util.List.of();

        String token = jwtUtil.generateToken(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getHospitalId(), // Include hospital_id for multi-tenant filtering
                hospital.getModules(),
                user.getBranchId(), // Multi Pharmacy branch login scoping
                // Tenant type gates hospital-only modules and controllers server-side.
                tenantType.name(),
                permissions,
                // Stamps the session generation current at login; a later password reset or role
                // change bumps the row and this token stops matching.
                user.getTokenVersion());

        // Create response
        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setUserId(user.getId());
        response.setName(user.getName());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());
        response.setHospitalId(user.getHospitalId());
        response.setHospitalName(hospital.getName());
        response.setModules(hospital.getModules());
        response.setReceptionMode(settings.getReceptionMode());
        response.setBillingHandler(settings.getBillingHandler());
        response.setIsSingleDoctor(hospital.getIsSingleDoctor());
        boolean inClinicAllowed = hospital.getModules() != null && hospital.getModules().contains("IN_CLINIC");
        response.setInClinic(inClinicAllowed && Boolean.TRUE.equals(settings.getInClinic()));
        response.setBarcodeEnabled(settings.getBarcodeEnabled() == null ? Boolean.TRUE : settings.getBarcodeEnabled());
        response.setBillPaymentTiming(settings.getBillPaymentTiming() == null ? "LAST" : settings.getBillPaymentTiming());
        response.setHospitalType(hospital.getType() != null ? hospital.getType().name() : "HOSPITAL");

        // Populate profile details
        populateProfileDetails(user, response, hospital);

        return response;
    }

    /**
     * Get current user profile with fresh hospital status/modules
     * 
     * @param email Email of the authenticated user
     * @return LoginResponse with updated details (token is null or ignored)
     */
    public LoginResponse getProfile(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        LoginResponse response = new LoginResponse();
        response.setToken(null);
        response.setUserId(user.getId());
        response.setName(user.getName());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());

        // Handle platform Super Admin bypass
        if ("SUPER_ADMIN".equals(user.getRole())) {
            response.setHospitalId(null);
            response.setHospitalName(null);
            response.setModules(null);
            response.setReceptionMode(null);
            response.setBillingHandler(null);
            response.setPhone(null);
            response.setAge(null);
            response.setGender(null);
            response.setSpecialization(null);
            return response;
        }

        if (user.getHospitalId() == null) {
            throw new UnauthorizedException("Invalid hospital user account");
        }

        // The tenant was re-checked here but the account was not, so a deactivated user kept a
        // valid profile — and the SETTINGS_UPDATED broadcast that bounces a blocked tenant's
        // clients had no equivalent for a deactivated person. Same check and same message as login.
        if (user.getIsActive() != null && !user.getIsActive()) {
            throw new UnauthorizedException("User account is inactive. Please contact administrator.");
        }

        Hospital hospital = hospitalRepository.findById(user.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        if (hospital.getIsActive() == null || !hospital.getIsActive()) {
            throw new UnauthorizedException("Hospital is inactive. Please contact support.");
        }

        HospitalSetting settings = hospitalSettingRepository.findByHospital_Id(hospital.getId())
                .orElseGet(() -> {
                    HospitalSetting newSettings = new HospitalSetting();
                    newSettings.setHospital(hospital);
                    newSettings.setReceptionMode("HAS_RECEPTIONIST");
                    newSettings.setBillingHandler("RECEPTIONIST");
                    newSettings.setInClinic(Boolean.TRUE);
                    return hospitalSettingRepository.save(newSettings);
                });

        // Restrict receptionist profile access if Solo Doctor mode is active
        if ("RECEPTIONIST".equals(user.getRole()) && "SOLO".equals(settings.getReceptionMode())) {
            throw new ForbiddenException("Solo Doctor Mode is active. Receptionist access is restricted.");
        }

        response.setHospitalId(user.getHospitalId());
        response.setHospitalName(hospital.getName());
        response.setModules(hospital.getModules());
        response.setReceptionMode(settings.getReceptionMode());
        response.setBillingHandler(settings.getBillingHandler());
        response.setIsSingleDoctor(hospital.getIsSingleDoctor());
        boolean inClinicAllowed = hospital.getModules() != null && hospital.getModules().contains("IN_CLINIC");
        response.setInClinic(inClinicAllowed && Boolean.TRUE.equals(settings.getInClinic()));
        response.setBarcodeEnabled(settings.getBarcodeEnabled() == null ? Boolean.TRUE : settings.getBarcodeEnabled());
        response.setBillPaymentTiming(settings.getBillPaymentTiming() == null ? "LAST" : settings.getBillPaymentTiming());
        response.setOtInchargeEnabled(settings.getOtInchargeEnabled() == null ? Boolean.FALSE : settings.getOtInchargeEnabled());
        response.setLogoUrl(hospital.getLogoUrl());
        response.setParentOrganization(hospital.getParentOrganization());
        response.setHospitalAddress(hospital.getAddress());
        response.setHospitalPhone(hospital.getPhone());
        response.setHospitalType(hospital.getType() != null ? hospital.getType().name() : "HOSPITAL");

        // Populate profile details
        populateProfileDetails(user, response, hospital);

        return response;
    }

    /**
     * Update current user profile details
     */
    @Transactional
    public LoginResponse updateProfile(String email, ProfileUpdateRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Password change handling
        if (request.getNewPassword() != null && !request.getNewPassword().trim().isEmpty()) {
            if (request.getCurrentPassword() == null || request.getCurrentPassword().trim().isEmpty()) {
                throw new IllegalArgumentException("Current password is required to change password");
            }
            if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
                throw new IllegalArgumentException("Incorrect current password");
            }
            user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        }

        // Name handling
        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            user.setName(request.getName());
        }
        userRepository.save(user);

        // Update profile in respective detailed actor tables
        if (!"SUPER_ADMIN".equals(user.getRole())) {
            if ("HOSPITAL_ADMIN".equals(user.getRole())) {
                Hospital hospital = hospitalRepository.findById(user.getHospitalId()).orElse(null);
                HospitalType entityType = hospital != null && hospital.getType() != null
                        ? hospital.getType() : HospitalType.HOSPITAL;

                if (entityType == HospitalType.CLINIC) {
                    ClinicAdmin admin = clinicAdminRepository.findByEmail(user.getEmail())
                            .orElseGet(() -> {
                                ClinicAdmin a = new ClinicAdmin();
                                a.setHospitalId(user.getHospitalId());
                                a.setEmail(user.getEmail());
                                a.setIsActive(true);
                                return a;
                            });
                    admin.setName(user.getName());
                    admin.setPhone(request.getPhone());
                    admin.setAge(request.getAge());
                    admin.setGender(request.getGender());
                    clinicAdminRepository.save(admin);
                } else if (entityType == HospitalType.PHARMACY) {
                    PharmacyAdmin admin = pharmacyAdminRepository.findByEmail(user.getEmail())
                            .orElseGet(() -> {
                                PharmacyAdmin a = new PharmacyAdmin();
                                a.setHospitalId(user.getHospitalId());
                                a.setEmail(user.getEmail());
                                a.setIsActive(true);
                                return a;
                            });
                    admin.setName(user.getName());
                    admin.setPhone(request.getPhone());
                    admin.setAge(request.getAge());
                    admin.setGender(request.getGender());
                    pharmacyAdminRepository.save(admin);
                } else {
                    HospitalAdmin admin = hospitalAdminRepository.findByEmail(user.getEmail())
                            .orElseGet(() -> {
                                HospitalAdmin newAdmin = new HospitalAdmin();
                                newAdmin.setHospitalId(user.getHospitalId());
                                newAdmin.setEmail(user.getEmail());
                                newAdmin.setIsActive(true);
                                return newAdmin;
                            });
                    admin.setName(user.getName());
                    admin.setPhone(request.getPhone());
                    admin.setAge(request.getAge());
                    admin.setGender(request.getGender());
                    hospitalAdminRepository.save(admin);
                }
                if (hospital != null) {
                    if (request.getHospitalName() != null && !request.getHospitalName().trim().isEmpty()) {
                        hospital.setName(request.getHospitalName());
                    }
                    if (request.getHospitalAddress() != null) {
                        hospital.setAddress(request.getHospitalAddress());
                    }
                    if (request.getHospitalPhone() != null) {
                        hospital.setPhone(request.getHospitalPhone());
                    }
                    if (request.getParentOrganization() != null) {
                        hospital.setParentOrganization(request.getParentOrganization());
                    }
                    if (request.getLogoUrl() != null) {
                        hospital.setLogoUrl(request.getLogoUrl());
                    }
                    hospitalRepository.save(hospital);
                }

                // Sync name, phone, and specialization to Doctor profile if single doctor mode is enabled
                if (hospital != null && Boolean.TRUE.equals(hospital.getIsSingleDoctor())) {
                    doctorRepository.findByEmailAndHospitalId(user.getEmail(), user.getHospitalId())
                            .ifPresent(doctor -> {
                                doctor.setName(user.getName());
                                doctor.setPhone(request.getPhone() != null ? request.getPhone() : "");
                                if (request.getSpecialization() != null && !request.getSpecialization().trim().isEmpty()) {
                                    doctor.setSpecialization(request.getSpecialization());
                                }
                                doctorRepository.save(doctor);
                            });
                }
            } else if ("RECEPTIONIST".equals(user.getRole())) {
                Receptionist receptionist = receptionistProfileRepository.findByEmail(user.getEmail())
                        .orElseGet(() -> {
                            Receptionist newRec = new Receptionist();
                            newRec.setHospitalId(user.getHospitalId());
                            newRec.setEmail(user.getEmail());
                            newRec.setIsActive(true);
                            return newRec;
                        });
                receptionist.setName(user.getName());
                receptionist.setPhone(request.getPhone());
                receptionist.setAge(request.getAge());
                receptionist.setGender(request.getGender());
                receptionistProfileRepository.save(receptionist);
            } else if ("PHARMACIST".equals(user.getRole())) {
                Pharmacist pharmacist = pharmacistProfileRepository.findByEmail(user.getEmail())
                        .orElseGet(() -> {
                            Pharmacist newPhar = new Pharmacist();
                            newPhar.setHospitalId(user.getHospitalId());
                            newPhar.setEmail(user.getEmail());
                            newPhar.setIsActive(true);
                            return newPhar;
                        });
                pharmacist.setName(user.getName());
                pharmacist.setPhone(request.getPhone());
                pharmacist.setAge(request.getAge());
                pharmacist.setGender(request.getGender());
                pharmacistProfileRepository.save(pharmacist);
            } else if ("DOCTOR".equals(user.getRole())) {
                Doctor doctor = doctorRepository.findByEmailAndHospitalId(user.getEmail(), user.getHospitalId())
                        .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
                doctor.setName(user.getName());
                doctor.setPhone(request.getPhone());
                if (request.getSpecialization() != null && !request.getSpecialization().trim().isEmpty()) {
                    doctor.setSpecialization(request.getSpecialization());
                }
                doctorRepository.save(doctor);
            }
        }

        return getProfile(email);
    }

    /**
     * Get hospital fees (consultation and case paper) for the hospital of the
     * authenticated user.
     */
    public com.hms.dto.HospitalFeesDTO getHospitalFees(String email) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getHospitalId() == null) throw new UnauthorizedException("Invalid hospital user account");
        Hospital hospital = hospitalRepository.findById(user.getHospitalId()).orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));
        com.hms.dto.HospitalFeesDTO dto = new com.hms.dto.HospitalFeesDTO();
        dto.setConsultationFee(hospital.getConsultationFee());
        dto.setCasePaperFee(hospital.getCasePaperFee());
        return dto;
    }

    /**
     * Update hospital fees. Only `HOSPITAL_ADMIN` role is allowed to update.
     */
    public com.hms.dto.HospitalFeesDTO updateHospitalFees(String email, com.hms.dto.HospitalFeesDTO fees) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getHospitalId() == null) throw new UnauthorizedException("Invalid hospital user account");
        if (!"HOSPITAL_ADMIN".equals(user.getRole())) {
            throw new ForbiddenException("Access denied: requires HOSPITAL_ADMIN role");
        }
        Hospital hospital = hospitalRepository.findById(user.getHospitalId()).orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));
        hospital.setConsultationFee(fees.getConsultationFee());
        hospital.setCasePaperFee(fees.getCasePaperFee());
        hospitalRepository.save(hospital);

        // Fees are money: the next OPD bill any receptionist or doctor raises must charge the new
        // amount, not the one their tab loaded hours ago. This was the only settings update in this
        // service that told nobody. Best-effort — a socket failure must not fail the save.
        try {
            webSocketHandler.broadcast(user.getHospitalId(), "{\"type\":\"SETTINGS_UPDATED\"}");
            webSocketHandler.broadcast(user.getHospitalId(), "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket fees update", e);
        }

        com.hms.dto.HospitalFeesDTO dto = new com.hms.dto.HospitalFeesDTO(hospital.getConsultationFee(), hospital.getCasePaperFee());
        return dto;
    }

    /**
     * Get operational settings (receptionMode and billingHandler) for the hospital of the authenticated user.
     */
    public HospitalSettingDTO getHospitalOperationsSettings(String email) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getHospitalId() == null) throw new UnauthorizedException("Invalid hospital user account");
        return hospitalSettingRepository.findByHospital_Id(user.getHospitalId())
                .map(this::toSettingDto)
                .orElse(new HospitalSettingDTO("HAS_RECEPTIONIST", "RECEPTIONIST", true));
    }

    /** Map a settings row to its DTO, including the print + payment-timing settings. */
    private HospitalSettingDTO toSettingDto(HospitalSetting s) {
        HospitalSettingDTO dto = new HospitalSettingDTO(s.getReceptionMode(), s.getBillingHandler(), s.getInClinic());
        dto.setBarcodeEnabled(s.getBarcodeEnabled() == null ? Boolean.TRUE : s.getBarcodeEnabled());
        dto.setSeparateNurseLogin(s.getSeparateNurseLogin() == null ? Boolean.FALSE : s.getSeparateNurseLogin());
        dto.setOtInchargeEnabled(s.getOtInchargeEnabled() == null ? Boolean.FALSE : s.getOtInchargeEnabled());
        dto.setPrintCasePaper(s.getPrintCasePaper() == null ? Boolean.TRUE : s.getPrintCasePaper());
        dto.setPrintBill(s.getPrintBill() == null ? Boolean.TRUE : s.getPrintBill());
        dto.setPrintPrescription(s.getPrintPrescription() == null ? Boolean.TRUE : s.getPrintPrescription());
        dto.setPrintInClinic(s.getPrintInClinic() == null ? Boolean.TRUE : s.getPrintInClinic());
        dto.setBillPaymentTiming(s.getBillPaymentTiming() == null ? "LAST" : s.getBillPaymentTiming());
        return dto;
    }

    /**
     * Update the Print Settings (which pages the consultation-complete print includes) and/or
     * the bill payment timing. Only HOSPITAL_ADMIN. Null fields are left unchanged.
     */
    @Transactional
    public HospitalSettingDTO updatePrintAndPaymentSettings(String email, HospitalSettingDTO dto) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getHospitalId() == null) throw new UnauthorizedException("Invalid hospital user account");
        if (!"HOSPITAL_ADMIN".equals(user.getRole())) {
            throw new ForbiddenException("Access denied: requires HOSPITAL_ADMIN role");
        }
        Hospital hospital = hospitalRepository.findById(user.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        HospitalSetting settings = hospitalSettingRepository.findByHospital_Id(user.getHospitalId())
                .orElseGet(() -> {
                    HospitalSetting n = new HospitalSetting();
                    n.setHospital(hospital);
                    return n;
                });

        if (dto.getPrintCasePaper() != null)   settings.setPrintCasePaper(dto.getPrintCasePaper());
        if (dto.getPrintBill() != null)        settings.setPrintBill(dto.getPrintBill());
        if (dto.getPrintPrescription() != null) settings.setPrintPrescription(dto.getPrintPrescription());
        if (dto.getPrintInClinic() != null)    settings.setPrintInClinic(dto.getPrintInClinic());
        if (dto.getBillPaymentTiming() != null) {
            String t = dto.getBillPaymentTiming().trim().toUpperCase();
            if (!"FIRST".equals(t) && !"LAST".equals(t)) {
                throw new IllegalArgumentException("billPaymentTiming must be FIRST or LAST");
            }
            settings.setBillPaymentTiming(t);
        }
        hospitalSettingRepository.save(settings);

        try {
            webSocketHandler.broadcast(user.getHospitalId(), "{\"type\":\"SETTINGS_UPDATED\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket settings update", e);
        }
        return toSettingDto(settings);
    }

    /**
     * Toggle whether nurses/nurse incharges log in via a separate nurse login
     * page. Kept as its own endpoint (mirrors {@link #updateBarcodeSetting})
     * so it can be flipped independently of the reception/billing form.
     */
    @Transactional
    public HospitalSettingDTO updateSeparateNurseLoginSetting(String email, boolean separateNurseLogin) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getHospitalId() == null) throw new UnauthorizedException("Invalid hospital user account");
        if (!"HOSPITAL_ADMIN".equals(user.getRole())) {
            throw new ForbiddenException("Access denied: requires HOSPITAL_ADMIN role");
        }

        Hospital hospital = hospitalRepository.findById(user.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        HospitalSetting settings = hospitalSettingRepository.findByHospital_Id(user.getHospitalId())
                .orElseGet(() -> {
                    HospitalSetting newSettings = new HospitalSetting();
                    newSettings.setHospital(hospital);
                    return newSettings;
                });
        settings.setSeparateNurseLogin(separateNurseLogin);
        hospitalSettingRepository.save(settings);

        try {
            webSocketHandler.broadcast(user.getHospitalId(), "{\"type\":\"SETTINGS_UPDATED\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket settings update", e);
        }

        HospitalSettingDTO dto = new HospitalSettingDTO(settings.getReceptionMode(), settings.getBillingHandler(), settings.getInClinic());
        dto.setBarcodeEnabled(settings.getBarcodeEnabled() == null ? Boolean.TRUE : settings.getBarcodeEnabled());
        dto.setSeparateNurseLogin(separateNurseLogin);
        dto.setOtInchargeEnabled(settings.getOtInchargeEnabled() == null ? Boolean.FALSE : settings.getOtInchargeEnabled());
        return dto;
    }

    /**
     * Toggle the OT Incharge setting.
     */
    @Transactional
    public HospitalSettingDTO updateOtInchargeSetting(String email, boolean otInchargeEnabled) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getHospitalId() == null) throw new UnauthorizedException("Invalid hospital user account");
        if (!"HOSPITAL_ADMIN".equals(user.getRole())) {
            throw new ForbiddenException("Access denied: requires HOSPITAL_ADMIN role");
        }

        Hospital hospital = hospitalRepository.findById(user.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        HospitalSetting settings = hospitalSettingRepository.findByHospital_Id(user.getHospitalId())
                .orElseGet(() -> {
                    HospitalSetting newSettings = new HospitalSetting();
                    newSettings.setHospital(hospital);
                    return newSettings;
                });
        settings.setOtInchargeEnabled(otInchargeEnabled);
        hospitalSettingRepository.save(settings);

        try {
            webSocketHandler.broadcast(user.getHospitalId(), "{\"type\":\"SETTINGS_UPDATED\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket settings update", e);
        }

        HospitalSettingDTO dto = new HospitalSettingDTO(settings.getReceptionMode(), settings.getBillingHandler(), settings.getInClinic());
        dto.setBarcodeEnabled(settings.getBarcodeEnabled() == null ? Boolean.TRUE : settings.getBarcodeEnabled());
        dto.setSeparateNurseLogin(settings.getSeparateNurseLogin() == null ? Boolean.FALSE : settings.getSeparateNurseLogin());
        dto.setOtInchargeEnabled(otInchargeEnabled);
        return dto;
    }

    /**
     * Update the pharmacy barcode workflow toggle. Only `HOSPITAL_ADMIN` may change it.
     * Kept separate from operations settings so pharmacy tenants (which have no
     * reception/billing config) can update it without tripping those validations.
     */
    @Transactional
    public HospitalSettingDTO updateBarcodeSetting(String email, boolean barcodeEnabled) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getHospitalId() == null) throw new UnauthorizedException("Invalid hospital user account");
        if (!"HOSPITAL_ADMIN".equals(user.getRole())) {
            throw new ForbiddenException("Access denied: requires HOSPITAL_ADMIN role");
        }

        Hospital hospital = hospitalRepository.findById(user.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        HospitalSetting settings = hospitalSettingRepository.findByHospital_Id(user.getHospitalId())
                .orElseGet(() -> {
                    HospitalSetting newSettings = new HospitalSetting();
                    newSettings.setHospital(hospital);
                    return newSettings;
                });
        settings.setBarcodeEnabled(barcodeEnabled);
        hospitalSettingRepository.save(settings);

        try {
            webSocketHandler.broadcast(user.getHospitalId(), "{\"type\":\"SETTINGS_UPDATED\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket settings update", e);
        }

        HospitalSettingDTO dto = new HospitalSettingDTO(settings.getReceptionMode(), settings.getBillingHandler(), settings.getInClinic());
        dto.setBarcodeEnabled(barcodeEnabled);
        dto.setSeparateNurseLogin(settings.getSeparateNurseLogin() == null ? Boolean.FALSE : settings.getSeparateNurseLogin());
        dto.setOtInchargeEnabled(settings.getOtInchargeEnabled() == null ? Boolean.FALSE : settings.getOtInchargeEnabled());
        return dto;
    }

    /**
     * Update operational settings. Only `HOSPITAL_ADMIN` role is allowed to update.
     */
    @Transactional
    public HospitalSettingDTO updateHospitalOperationsSettings(String email, HospitalSettingDTO dto) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getHospitalId() == null) throw new UnauthorizedException("Invalid hospital user account");
        if (!"HOSPITAL_ADMIN".equals(user.getRole())) {
            throw new ForbiddenException("Access denied: requires HOSPITAL_ADMIN role");
        }

        // Normalize: trim whitespace and uppercase to be tolerant of minor client variations
        String receptionMode = dto.getReceptionMode() == null ? null : dto.getReceptionMode().trim().toUpperCase();
        String billingHandler = dto.getBillingHandler() == null ? null : dto.getBillingHandler().trim().toUpperCase();

        // Guard: validate normalized values against allowed domains
        if (!"HAS_RECEPTIONIST".equals(receptionMode) && !"SOLO".equals(receptionMode)) {
            throw new IllegalArgumentException("receptionMode must be HAS_RECEPTIONIST or SOLO");
        }
        if (!"RECEPTIONIST".equals(billingHandler) && !"DOCTOR".equals(billingHandler) && !"BOTH".equals(billingHandler)) {
            throw new IllegalArgumentException("billingHandler must be RECEPTIONIST, DOCTOR, or BOTH");
        }

        // Cross-field invariant: SOLO mode has no receptionist, so billing must be handled by DOCTOR.
        // Override any conflicting client-supplied billingHandler when switching to SOLO.
        if ("SOLO".equals(receptionMode)) {
            billingHandler = "DOCTOR";
        }

        final String effectiveBillingHandler = billingHandler;
        Boolean inClinicRequested = dto.getInClinic();

        // Enforce plan gate: only allow inClinic=true if IN_CLINIC module is in hospital modules.
        // null means "don't change" — preserve the existing value in that case.
        Hospital hospital = hospitalRepository.findById(user.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));
        boolean inClinicModuleEnabled = hospital.getModules() != null && hospital.getModules().contains("IN_CLINIC");
        Boolean inClinic;
        if (inClinicRequested == null) {
            inClinic = null; // preserve existing value downstream
        } else {
            inClinic = inClinicModuleEnabled ? inClinicRequested : Boolean.FALSE;
        }

        // Ensure a settings row exists; if not, create one first
        HospitalSetting settings = hospitalSettingRepository.findByHospital_Id(user.getHospitalId())
                .orElseGet(() -> {
                    HospitalSetting newSettings = new HospitalSetting();
                    newSettings.setHospital(hospital);
                    newSettings.setReceptionMode(receptionMode != null ? receptionMode : "HAS_RECEPTIONIST");
                    newSettings.setBillingHandler(effectiveBillingHandler != null ? effectiveBillingHandler : "RECEPTIONIST");
                    newSettings.setInClinic(inClinic != null ? inClinic : Boolean.FALSE);
                    return hospitalSettingRepository.save(newSettings);
                });

        // Use a direct JPQL UPDATE — never touches hospital_id, eliminates detached-proxy issues
        hospitalSettingRepository.updateByHospitalId(
                user.getHospitalId(),
                receptionMode,
                billingHandler,
                inClinic != null ? inClinic : settings.getInClinic()
        );

        // Broadcast so every connected client for this hospital (admin, doctor,
        // receptionist) re-fetches its profile and re-renders tabs in real time —
        // e.g. hiding the Medicine Inventory tab when In-Clinic is turned off.
        try {
            webSocketHandler.broadcast(user.getHospitalId(), "{\"type\":\"SETTINGS_UPDATED\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket settings update", e);
        }

        HospitalSettingDTO responseDto = new HospitalSettingDTO(receptionMode, billingHandler,
                inClinic != null ? inClinic : settings.getInClinic());
        responseDto.setBarcodeEnabled(settings.getBarcodeEnabled() == null ? Boolean.TRUE : settings.getBarcodeEnabled());
        responseDto.setSeparateNurseLogin(settings.getSeparateNurseLogin() == null ? Boolean.FALSE : settings.getSeparateNurseLogin());
        responseDto.setOtInchargeEnabled(settings.getOtInchargeEnabled() == null ? Boolean.FALSE : settings.getOtInchargeEnabled());
        return responseDto;
    }
}

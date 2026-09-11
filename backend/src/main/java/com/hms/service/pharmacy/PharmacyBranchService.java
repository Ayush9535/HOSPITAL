package com.hms.service.pharmacy;
import com.hms.util.LogSanitizer;

import com.hms.entity.Plan;
import com.hms.entity.User;
import com.hms.entity.pharmacy.PharmacyBranch;
import com.hms.repository.HospitalPlanSubscriptionRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.pharmacy.PharmacyBranchRepository;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages Multi Pharmacy branches (outlets). Each branch has one PHARMACIST login.
 * Branch count is capped by the tenant's plan maxOutlets (null = unlimited).
 */
@Service
public class PharmacyBranchService {

    private static final Logger logger = LoggerFactory.getLogger(PharmacyBranchService.class);

    @Autowired private PharmacyBranchRepository branchRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private HospitalPlanSubscriptionRepository subscriptionRepository;
    @Autowired private HospitalWebSocketHandler webSocketHandler;

    public List<PharmacyBranch> list() {
        Long hospitalId = requireHospitalId();
        return branchRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtAsc(hospitalId);
    }

    @Transactional
    public PharmacyBranch create(String name, String address, String phone, String email, String password) {
        Long hospitalId = requireHospitalId();
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Branch name is required");
        if (email == null || email.trim().isEmpty()) throw new IllegalArgumentException("Branch login email is required");
        if (password == null || password.trim().isEmpty()) throw new IllegalArgumentException("Branch login password is required");

        enforceOutletLimit(hospitalId);

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already exists");
        }

        PharmacyBranch branch = new PharmacyBranch();
        branch.setHospitalId(hospitalId);
        branch.setName(name.trim());
        branch.setAddress(address);
        branch.setPhone(phone);
        branch.setIsActive(true);
        branch = branchRepository.save(branch);

        // One PHARMACIST login per branch, tagged with branch_id.
        User login = new User();
        login.setName(name.trim());
        login.setEmail(email.trim());
        login.setPassword(passwordEncoder.encode(password));
        login.setRole("PHARMACIST");
        login.setHospitalId(hospitalId);
        login.setBranchId(branch.getId());
        login.setIsActive(true);
        User savedLogin = userRepository.save(login);

        branch.setLoginUserId(savedLogin.getId());
        branch = branchRepository.save(branch);

        broadcast(hospitalId);
        logger.info("Created pharmacy branch {} (login {}) for hospital {}", branch.getId(), LogSanitizer.clean(email), hospitalId);
        return branch;
    }

    @Transactional
    public PharmacyBranch update(Long id, String name, String address, String phone) {
        Long hospitalId = requireHospitalId();
        PharmacyBranch branch = getOwned(id, hospitalId);
        if (name != null && !name.trim().isEmpty()) branch.setName(name.trim());
        branch.setAddress(address);
        branch.setPhone(phone);
        branch = branchRepository.save(branch);
        broadcast(hospitalId);
        return branch;
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        Long hospitalId = requireHospitalId();
        if (newPassword == null || newPassword.trim().isEmpty()) throw new IllegalArgumentException("New password is required");
        PharmacyBranch branch = getOwned(id, hospitalId);
        if (branch.getLoginUserId() == null) throw new ResourceNotFoundException("Branch has no login user");
        User login = userRepository.findById(branch.getLoginUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Branch login user not found"));
        login.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(login);
        logger.info("Reset password for pharmacy branch {}", LogSanitizer.clean(id));
    }

    @Transactional
    public void delete(Long id) {
        Long hospitalId = requireHospitalId();
        PharmacyBranch branch = getOwned(id, hospitalId);
        branch.setIsActive(false);
        branchRepository.save(branch);
        // Disable the branch login so it can no longer sign in.
        if (branch.getLoginUserId() != null) {
            userRepository.findById(branch.getLoginUserId()).ifPresent(u -> {
                u.setIsActive(false);
                userRepository.save(u);
            });
        }
        broadcast(hospitalId);
        logger.info("Deleted (soft) pharmacy branch {}", LogSanitizer.clean(id));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void enforceOutletLimit(Long hospitalId) {
        Integer maxOutlets = subscriptionRepository.findByHospitalIdAndIsCurrentTrue(hospitalId)
                .map(sub -> sub.getPlan())
                .map(Plan::getMaxOutlets)
                .orElse(null);
        if (maxOutlets != null) {
            long current = branchRepository.countByHospitalIdAndIsActiveTrue(hospitalId);
            if (current >= maxOutlets) {
                throw new IllegalArgumentException("Maximum outlets reached (" + maxOutlets + "). Upgrade the plan to add more branches.");
            }
        }
    }

    private PharmacyBranch getOwned(Long id, Long hospitalId) {
        return branchRepository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }

    private void broadcast(Long hospitalId) {
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after branch change", e);
        }
    }
}

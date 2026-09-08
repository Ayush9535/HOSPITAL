package com.hms.service.platform;

import com.hms.dto.AssignPlanRequest;
import com.hms.dto.CreatePlanRequest;
import com.hms.dto.SubscriptionInfoDTO;
import com.hms.entitlement.EntitlementRegistry;
import com.hms.entity.*;
import com.hms.exception.ResourceNotFoundException;
import com.hms.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class PlatformPlanService {

    private static final Logger logger = LoggerFactory.getLogger(PlatformPlanService.class);

    @Autowired private PlanRepository planRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private HospitalPlanSubscriptionRepository subscriptionRepository;
    @Autowired private HospitalSettingRepository hospitalSettingRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    @Autowired
    private com.hms.service.RealtimeNotifier notifier;

    // ─── Plan CRUD ─────────────────────────────────────────────────────────

    @Transactional
    public Plan createPlan(CreatePlanRequest req) {
        Plan plan = new Plan();
        plan.setName(req.getName());
        plan.setType(HospitalType.valueOf(req.getType()));
        plan.setMonthlyPrice(req.getMonthlyPrice());
        plan.setYearlyPrice(req.getYearlyPrice());
        plan.setModules(EntitlementRegistry.normalizePlanModules(plan.getType(), req.getModules()));
        plan.setFeatures(req.getFeatures() != null ? req.getFeatures() : new ArrayList<>());
        plan.setInClinic(Boolean.TRUE.equals(req.getInClinic()));
        applyOutletSettings(plan, req);
        ensurePharmacyBaseModule(plan);
        plan.setIsActive(true);
        Plan saved = planRepository.save(plan);
        logAction("PLAN_CREATED", "Created plan: " + saved.getName() + " [" + saved.getType() + "]");
        return saved;
    }

    /**
     * Multi-outlet is a PHARMACY-only capability (one owner, several medical shops).
     * For any other plan type it is forced off so the flag can never leak into
     * hospital/clinic plans. When enabled, maxOutlets (null = unlimited) is preserved.
     */
    private void applyOutletSettings(Plan plan, CreatePlanRequest req) {
        boolean isPharmacy = plan.getType() == HospitalType.PHARMACY;
        boolean multiOutlet = isPharmacy && plan.getModules().contains(EntitlementRegistry.TIER_MULTI_PHARMACY);
        plan.setMultiOutlet(multiOutlet);
        plan.setMaxOutlets(multiOutlet ? req.getMaxOutlets() : null);
    }

    /**
     * Pharmacy tenants are detected across the app by the "PHARMACY" module. The
     * pharmacy plan tiers (SINGLE_PHARMACY / SINGLE_PHARMACIST_ADMIN / MULTI_PHARMACY)
     * drive the mode, but we also keep the base PHARMACY module present so existing
     * detection (standalone-pharmacy gating, dashboards) keeps working.
     */
    private void ensurePharmacyBaseModule(Plan plan) {
        if (plan.getType() == HospitalType.PHARMACY && plan.getModules() != null
                && !plan.getModules().contains("PHARMACY")) {
            plan.getModules().add("PHARMACY");
        }
    }

    @Transactional
    public Plan updatePlan(String publicId, CreatePlanRequest req) {
        Plan plan = planRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + publicId));

        plan.setName(req.getName());
        plan.setMonthlyPrice(req.getMonthlyPrice());
        plan.setYearlyPrice(req.getYearlyPrice());
        plan.setInClinic(Boolean.TRUE.equals(req.getInClinic()));
        applyOutletSettings(plan, req);

        if (req.getModules() != null) {
            plan.getModules().clear();
            plan.getModules().addAll(EntitlementRegistry.normalizePlanModules(plan.getType(), req.getModules()));
        }
        if (req.getFeatures() != null) {
            plan.getFeatures().clear();
            plan.getFeatures().addAll(req.getFeatures());
        }
        ensurePharmacyBaseModule(plan);

        Plan saved = planRepository.save(plan);
        propagateModulesToSubscribers(saved);
        logAction("PLAN_UPDATED", "Updated plan: " + saved.getName());
        return saved;
    }

    @Transactional
    public void deletePlan(String publicId) {
        Plan plan = planRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + publicId));

        long activeCount = subscriptionRepository.countByPlan_IdAndIsCurrentTrue(plan.getId());
        if (activeCount > 0) {
            throw new IllegalArgumentException(
                "This plan is assigned to " + activeCount + " active entities. Reassign them before deleting.");
        }

        planRepository.delete(plan);
        logAction("PLAN_DELETED", "Deleted plan: " + plan.getName());
    }

    public List<Plan> getAllPlans() {
        return planRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Plan> getPlansByType(HospitalType type) {
        return planRepository.findByTypeOrderByCreatedAtDesc(type);
    }

    // ─── Plan Assignment ────────────────────────────────────────────────────

    @Transactional
    public HospitalPlanSubscription assignPlan(String planPublicId, AssignPlanRequest req) {
        Plan plan = planRepository.findByPublicId(planPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + planPublicId));

        Hospital hospital = hospitalRepository.findByPublicId(req.getHospitalPublicId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital/Clinic/Pharmacy not found"));

        if (plan.getType() != hospital.getType()) {
            throw new IllegalArgumentException(
                "Plan type '" + plan.getType() + "' does not match entity type '" + hospital.getType() + "'");
        }

        BillingPeriod period = BillingPeriod.valueOf(req.getBillingPeriod());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = period == BillingPeriod.MONTHLY ? now.plusMonths(1) : now.plusYears(1);

        subscriptionRepository.deactivateCurrentSubscription(hospital.getId());

        HospitalPlanSubscription sub = new HospitalPlanSubscription();
        sub.setHospitalId(hospital.getId());
        sub.setPlan(plan);
        sub.setBillingPeriod(period);
        sub.setAssignedAt(now);
        sub.setExpiresAt(expiresAt);
        sub.setIsCurrent(true);
        sub.setAssignedBy(resolveCurrentUserId());
        HospitalPlanSubscription saved = subscriptionRepository.save(sub);

        applyPlanToHospital(hospital, plan);

        logAction("PLAN_ASSIGNED",
            "Assigned plan '" + plan.getName() + "' to '" + hospital.getName() + "' [" + period + "]");
        return saved;
    }

    // ─── Subscription Info for Hospital Admin ───────────────────────────────

    public SubscriptionInfoDTO getSubscriptionInfo(Long hospitalId) {
        HospitalPlanSubscription sub = subscriptionRepository
                .findByHospitalIdAndIsCurrentTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found"));

        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        SubscriptionInfoDTO dto = new SubscriptionInfoDTO();
        dto.setPlanName(sub.getPlan().getName());
        dto.setPlanType(sub.getPlan().getType().name());
        dto.setBillingPeriod(sub.getBillingPeriod().name());
        dto.setMonthlyPrice(sub.getPlan().getMonthlyPrice());
        dto.setYearlyPrice(sub.getPlan().getYearlyPrice());
        dto.setFeatures(sub.getPlan().getFeatures());
        dto.setAssignedAt(sub.getAssignedAt());
        dto.setExpiresAt(sub.getExpiresAt());
        dto.setSubscriptionStatus(hospital.getSubscriptionStatus());
        return dto;
    }

    // ─── Internal helpers ───────────────────────────────────────────────────

    private void propagateModulesToSubscribers(Plan plan) {
        List<HospitalPlanSubscription> currentSubs =
                subscriptionRepository.findByPlan_IdAndIsCurrentTrue(plan.getId());
        for (HospitalPlanSubscription sub : currentSubs) {
            hospitalRepository.findById(sub.getHospitalId()).ifPresent(h -> applyPlanToHospital(h, plan));
        }
    }

    private void applyPlanToHospital(Hospital hospital, Plan plan) {
        ArrayList<String> modules = new ArrayList<>(
                EntitlementRegistry.normalizeAppliedPlanModules(plan.getType(), plan.getModules()));
        if (Boolean.TRUE.equals(plan.getInClinic())) {
            if (!modules.contains("IN_CLINIC")) modules.add("IN_CLINIC");
        } else {
            modules.remove("IN_CLINIC");
        }
        hospital.setModules(modules);
        hospital.setSubscriptionStatus("ACTIVE");
        hospitalRepository.save(hospital);

        boolean inClinicEnabled = Boolean.TRUE.equals(plan.getInClinic());
        hospitalSettingRepository.findByHospital(hospital).ifPresent(setting -> {
            setting.setInClinic(inClinicEnabled);
            hospitalSettingRepository.save(setting);
        });

        // Every plan change funnels through here (assignPlan and a plan edit propagating to its
        // subscribers), so this is the one place that has to tell the tenant its plan moved.
        notifyPlanChanged(hospital.getId());
    }

    /**
     * Push a plan change to everyone currently signed in at that tenant, so modules appear and
     * disappear live instead of on the next login.
     *
     * SETTINGS_UPDATED makes each client re-read /auth/me (which returns the hospital's modules
     * from the DB), and REFRESH_DATA reloads the lists the new modules unlock. The server side
     * needs no message at all: ModuleAccessAspect reads modules from the hospital row, so the
     * new plan is enforced on the very next request regardless of what any old JWT claims.
     *
     * Fired AFTER the transaction commits, never inside it: a client that re-fetched /auth/me
     * while this transaction was still open would read the OLD modules and cache them, which is
     * exactly the staleness this is meant to remove. Best-effort — a socket problem must never
     * roll back a paid plan change.
     */
    private void notifyPlanChanged(Long hospitalId) {
        if (hospitalId == null) return;
        Runnable push = () -> {
            try {
                webSocketHandler.broadcast(hospitalId, "{\"type\":\"SETTINGS_UPDATED\"}");
                webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
            } catch (Exception e) {
                logger.warn("Failed to broadcast plan change to hospital {}", hospitalId, e);
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

    private Long resolveCurrentUserId() {
        try {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            return userRepository.findByEmail(email).map(u -> u.getId()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void logAction(String action, String details) {
        try {
            String performedBy = SecurityContextHolder.getContext().getAuthentication().getName();
            AuditLog log = new AuditLog();
            log.setAction(action);
            log.setDetails(details);
            log.setPerformedBy(performedBy);
            auditLogRepository.save(log);
        } catch (Exception e) {
            logger.error("Audit log failed: {}", e.getMessage(), e);
        }
    }
}

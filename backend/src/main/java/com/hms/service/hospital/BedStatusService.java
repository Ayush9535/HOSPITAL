package com.hms.service.hospital;

import com.hms.entity.Bed;
import com.hms.entity.BedStatus;
import com.hms.entity.BedStatusAudit;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.BedRepository;
import com.hms.repository.BedStatusAuditRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * BedStatusService - the single place a bed's status changes (Nursing Mgmt
 * Phase C). Every transition records previous -> new into bed_status_audits
 * plus a best-effort general audit entry. Ward scoping for user-initiated
 * changes is applied by the caller (BedController) via NurseInchargeGuard;
 * system transitions (admit/discharge/transfer/OT) call change() directly.
 */
@Service
public class BedStatusService {

    private static final Logger logger = LoggerFactory.getLogger(BedStatusService.class);

    @Autowired private BedRepository bedRepository;
    @Autowired private BedStatusAuditRepository auditRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;

    /**
     * E1 (C3) — locks one bed for the rest of the caller's transaction and returns it.
     *
     * <p>Claiming a bed is check-then-act: {@code admitFromOpd} and {@code changeBed} both read
     * the status and write it later, so two callers could each see a free bed and both take it.
     * Bed availability cannot be expressed as a unique index, so the row is locked while the
     * caller re-checks and claims — the same reasoning, and the same mechanism, as
     * {@code OtSchedulingService.lockRoom} uses for a theatre.
     *
     * <p>The caller re-checks the status itself, because "claimable" differs by path: an
     * admission needs an AVAILABLE bed, while a transfer may move onto a bed that is merely not
     * occupied. Both must do that re-check AFTER this call and throw
     * {@link com.hms.exception.ConflictException} when it fails.
     *
     * <p>{@code MANDATORY} is deliberate: a PESSIMISTIC_WRITE lock is released as soon as its
     * transaction ends, so calling this without one would hold the lock for a single statement
     * and protect nothing — a lock that reviews as correct and prevents nothing. This makes that
     * mistake impossible instead of merely documented.
     *
     * @throws ResourceNotFoundException if the bed does not exist, or belongs to another tenant —
     *         indistinguishable from missing, as everywhere else (C4).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Bed lockForClaim(Long bedId) {
        Long hospitalId = safeHospitalId();
        return bedRepository.findByBedIdAndHospitalIdForUpdate(bedId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Bed not found"));
    }

    @Transactional
    public Bed change(Long bedId, String newStatus, String remarks) {
        Long hospitalId = safeHospitalId();
        Bed bed = bedRepository.findById(bedId)
                .orElseThrow(() -> new IllegalArgumentException("Bed not found"));
        if (hospitalId != null && !hospitalId.equals(bed.getHospitalId())) {
            // Phase 2.1: a tenant check, not a permission check -- another hospital's bed must
            // be indistinguishable from a missing one.
            throw new ResourceNotFoundException("Bed not found");
        }
        return applyChange(bed, newStatus, remarks);
    }

    /**
     * Completes a transition using a row the caller has already locked. This preserves the
     * central status/audit behavior without replacing the caller's pessimistic lock.
     */
    @Transactional
    public Bed changeLocked(Bed bed, String newStatus, String remarks) {
        if (bed == null) throw new IllegalArgumentException("Bed not found");
        Long hospitalId = safeHospitalId();
        if (hospitalId != null && !hospitalId.equals(bed.getHospitalId())) {
            throw new ResourceNotFoundException("Bed not found");
        }
        return applyChange(bed, newStatus, remarks);
    }

    private Bed applyChange(Bed bed, String newStatus, String remarks) {
        if (!BedStatus.isValid(newStatus)) throw new IllegalArgumentException("Unknown bed status: " + newStatus);

        String previous = bed.getStatus();
        bed.setStatus(newStatus);
        if (!BedStatus.OCCUPIED.equals(newStatus)) bed.setCurrentIpdAdmissionId(null);
        Bed saved = bedRepository.save(bed);

        BedStatusAudit a = new BedStatusAudit();
        a.setHospitalId(bed.getHospitalId());
        a.setBedId(bed.getBedId());
        a.setWardId(bed.getWardId());
        a.setPreviousStatus(previous);
        a.setNewStatus(newStatus);
        a.setChangedByUserId(safeUserId());
        a.setRemarks(remarks);
        auditRepository.save(a);

        try {
            auditLogService.logAction("BED_STATUS_CHANGED", previous + " -> " + newStatus,
                    securityHelper.getCurrentUserEmail(), bed.getHospitalId(), "BED", String.valueOf(bed.getBedId()), remarks);
        } catch (Exception e) { logger.warn("Bed status audit log failed: {}", e.getMessage()); }
        // Bed occupancy is shared state: reception admits against it, nursing cleans it, OT frees
        // it. Every bed board in the tenant must move at once or two people book the same bed.
        notifier.refresh(bed.getHospitalId());
        return saved;
    }

    public List<BedStatusAudit> history(Long bedId) {
        Long hospitalId = safeHospitalId();
        Bed bed = bedRepository.findById(bedId).orElseThrow(() -> new IllegalArgumentException("Bed not found"));
        if (hospitalId != null && !hospitalId.equals(bed.getHospitalId())) {
            // Phase 2.1: a tenant check, not a permission check -- another hospital's bed must
            // be indistinguishable from a missing one.
            throw new ResourceNotFoundException("Bed not found");
        }
        return auditRepository.findByBedIdOrderByChangedAtDesc(bedId);
    }

    private Long safeHospitalId() {
        try { return securityHelper.getCurrentHospitalId(); } catch (Exception e) { return null; }
    }
    private Long safeUserId() {
        try { return securityHelper.getCurrentUserId(); } catch (Exception e) { return null; }
    }
}

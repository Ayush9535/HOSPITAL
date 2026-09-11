package com.hms.service.hospital;

import com.hms.dto.*;
import com.hms.entity.Bed;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.WardRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.security.HospitalWebSocketHandler;

import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class WardService {

    private static final Logger logger = LoggerFactory.getLogger(WardService.class);

    /** Upper bound on beds auto-created for one ward — a domain sanity cap and an overflow guard. */
    private static final int MAX_BEDS_PER_WARD = 2000;

    private final WardRepository wardRepository;
    private final BedRepository bedRepository;
    private final SecurityContextHelper securityHelper;
    private final HospitalWebSocketHandler webSocketHandler;
    private final com.hms.repository.NurseProfileRepository nurseProfileRepository;
    private final com.hms.service.AuditLogService auditLogService;

    public WardService(WardRepository wardRepository, BedRepository bedRepository,
                       SecurityContextHelper securityHelper,
                       HospitalWebSocketHandler webSocketHandler,
                       com.hms.repository.NurseProfileRepository nurseProfileRepository,
                       com.hms.service.AuditLogService auditLogService) {
        this.wardRepository = wardRepository;
        this.bedRepository = bedRepository;
        this.securityHelper = securityHelper;
        this.webSocketHandler = webSocketHandler;
        this.nurseProfileRepository = nurseProfileRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void setIncharge(Long wardId, Long inchargeNurseProfileId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        Ward ward = wardRepository.findById(wardId)
                .orElseThrow(() -> new IllegalArgumentException("Ward not found"));
        if (!hospitalId.equals(ward.getHospitalId())) {
            // Phase 2.1: a tenant check, not a permission check -- another hospital's ward must
            // be indistinguishable from a missing one. A 401 would confirm the row exists
            // elsewhere and log the caller out of a session that is perfectly valid.
            throw new ResourceNotFoundException("Ward not found");
        }
        Long previous = ward.getInchargeNurseId();
        if (inchargeNurseProfileId != null) {
            com.hms.entity.NurseProfile p = nurseProfileRepository.findById(inchargeNurseProfileId)
                    .orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
            if (!hospitalId.equals(p.getHospitalId()) || !Boolean.TRUE.equals(p.getIsActive())
                    || !Boolean.TRUE.equals(p.getIsIncharge())) {
                throw new IllegalArgumentException("Target must be an active Nurse Incharge in this hospital");
            }
        }
        ward.setInchargeNurseId(inchargeNurseProfileId);
        wardRepository.save(ward);
        auditLogService.logAction("WARD_INCHARGE_SET",
                "Ward " + ward.getWardName() + " incharge " + previous + " -> " + inchargeNurseProfileId,
                securityHelper.getCurrentUserEmail(), hospitalId, "WARD", String.valueOf(wardId), null);
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after ward incharge set", e);
        }
    }

    @Transactional
    public WardResponse createWard(CreateWardRequest req) {
        Long hospitalId = securityHelper.getCurrentHospitalId();

        Ward ward = new Ward();
        ward.setHospitalId(hospitalId);
        ward.setWardName(req.getWardName());
        ward.setBedPrice(req.getBedPrice());
        ward.setTotalBeds(req.getTotalBeds());
        ward.setFloorNumber(req.getFloorNumber());
        // ICU Phase 2: null/blank normalises to GENERAL, so a client that never sends the
        // field keeps creating general wards exactly as it did before.
        ward.setUnitType(com.hms.service.hospital.icu.CareUnitRegistry.normalize(req.getUnitType()));

        Ward saved = wardRepository.save(ward);

        // auto-create beds
        int total = req.getTotalBeds() == null ? 0 : req.getTotalBeds();
        // Bound the user-supplied count to a sane maximum. Besides being a domain rule (no real
        // ward has thousands of beds), this stops a huge value from overflowing the bed-number
        // arithmetic below and from creating a runaway number of rows (CodeQL: user-controlled
        // data in arithmetic expression).
        if (total < 0 || total > MAX_BEDS_PER_WARD) {
            throw new IllegalArgumentException(
                    "Total beds must be between 0 and " + MAX_BEDS_PER_WARD);
        }
        // ensure unique bed codes within a ward by checking existing highest index
        int startIndex = 1;
        List<Bed> existing = bedRepository.findByWardIdAndHospitalId(saved.getWardId(), hospitalId);
        if (existing != null && !existing.isEmpty()) {
            int max = existing.stream().mapToInt(bd -> {
                String code = bd.getBedCode();
                try {
                    int idx = Integer.parseInt(code.replaceAll(".*[^0-9](?=\\d+$)", ""));
                    return idx;
                } catch (Exception ex) { return 0; }
            }).max().orElse(0);
            startIndex = max + 1;
        }

        for (int i = 0; i < total; i++) {
            long bedNumber = (long) startIndex + i;   // long so a high startIndex can never overflow
            Bed b = new Bed();
            b.setHospitalId(hospitalId);
            b.setWardId(saved.getWardId());
            b.setBedCode(String.format("%s-B%d", req.getWardName(), bedNumber));
            b.setStatus("available");
            bedRepository.save(b);
        }

        // Broadcast real-time refresh
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after ward creation", e);
        }

        return toResponse(saved);
    }

    @Transactional
    public List<WardResponse> bulkCreate(BulkCreateWardsRequest req) {
        return req.getWards().stream().map(this::createWard).collect(Collectors.toList());
    }

    public List<WardResponse> getAllWards() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return wardRepository.findByHospitalId(hospitalId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * Wards eligible for IPD admission/bed selection. A ward with no Available bed is always
     * hidden (a bed awaiting cleaning or under maintenance does not count). Nursing assignment
     * metadata must not hide an otherwise usable ward from the admission workflow.
     */
    public List<WardResponse> getWardsForAdmission() {
        Long hospitalId = securityHelper.getCurrentHospitalId();

        return wardRepository.findByHospitalId(hospitalId)
                .stream()
                .filter(w -> bedRepository.findByWardIdAndHospitalId(w.getWardId(), hospitalId).stream()
                        .anyMatch(b -> com.hms.entity.BedStatus.AVAILABLE.equalsIgnoreCase(b.getStatus())))
                .map(this::toResponse).collect(Collectors.toList());
    }

    public List<BedResponse> getBedsForWard(Long wardId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return bedRepository.findByWardIdAndHospitalId(wardId, hospitalId)
                .stream().map(b -> {
                    BedResponse br = new BedResponse();
                    br.setBedId(b.getBedId());
                    br.setBedCode(b.getBedCode());
                    br.setStatus(b.getStatus());
                    return br;
                }).collect(Collectors.toList());
    }

    @Transactional
    public WardResponse updateWard(Long wardId, UpdateWardRequest req) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        Ward w = wardRepository.findById(wardId).orElseThrow(() -> new ResourceNotFoundException("Ward not found"));
        // Phase 2.1: a tenant check, not a permission check -- another hospital's ward must be
        // indistinguishable from the missing ward reported one line above.
        if (!w.getHospitalId().equals(hospitalId)) throw new ResourceNotFoundException("Ward not found");

        if (req.getWardName() != null) w.setWardName(req.getWardName());
        if (req.getBedPrice() != null) w.setBedPrice(req.getBedPrice());
        if (req.getFloorNumber() != null) w.setFloorNumber(req.getFloorNumber());
        if (req.getUnitType() != null) applyUnitType(w, req.getUnitType(), hospitalId);

        // Bed count is editable: resize the ward's bed list to match. Done after the rename
        // above so any newly created bed codes carry the ward's new name.
        if (req.getTotalBeds() != null) {
            resizeBeds(w, req.getTotalBeds(), hospitalId);
        }

        Ward saved = wardRepository.save(w);

        // Broadcast real-time refresh
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after ward update", e);
        }

        return toResponse(saved);
    }

    @Transactional
    public void deleteWard(Long wardId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        Ward w = wardRepository.findById(wardId).orElseThrow(() -> new ResourceNotFoundException("Ward not found"));
        // Phase 2.1: a tenant check, not a permission check -- another hospital's ward must be
        // indistinguishable from the missing ward reported one line above.
        if (!w.getHospitalId().equals(hospitalId)) throw new ResourceNotFoundException("Ward not found");

        List<Bed> beds = bedRepository.findByWardIdAndHospitalId(wardId, hospitalId);
        boolean hasOccupied = beds.stream().anyMatch(b -> !"available".equalsIgnoreCase(b.getStatus()));
        if (hasOccupied) throw new IllegalArgumentException("Cannot delete ward with occupied beds");

        // A ward with nurses assigned to it cannot be deleted — reassign those
        // nurses to another ward first.
        long assignedNurses = nurseProfileRepository.countByWardIdAndIsActiveTrue(wardId);
        if (assignedNurses > 0) {
            throw new IllegalArgumentException(
                "Cannot delete ward: " + assignedNurses + " nurse(s) are assigned to it. Reassign them to another ward first.");
        }

        bedRepository.deleteAll(beds);
        wardRepository.delete(w);

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after ward deletion", e);
        }
    }

    /** Trailing digits of a bed code, e.g. "ICU-B12" -> 12. */
    private static final java.util.regex.Pattern BED_INDEX = java.util.regex.Pattern.compile("(\\d+)$");

    private int bedIndex(Bed b) {
        if (b.getBedCode() == null) return 0;
        java.util.regex.Matcher m = BED_INDEX.matcher(b.getBedCode());
        try {
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Resizes a ward's bed list to {@code target}.
     *
     * Growing appends new available beds, numbered from the current highest index so codes
     * stay unique even after earlier beds were removed. Shrinking deletes only AVAILABLE
     * beds, highest-numbered first — a bed that is occupied, awaiting cleaning, or under
     * maintenance is never destroyed, so the request is rejected rather than silently
     * dropping a patient's bed.
     */
    private void resizeBeds(Ward ward, int target, Long hospitalId) {
        if (target < 0) throw new IllegalArgumentException("Total beds cannot be negative");

        List<Bed> beds = bedRepository.findByWardIdAndHospitalId(ward.getWardId(), hospitalId);
        int current = beds.size();

        if (target > current) {
            int next = beds.stream().mapToInt(this::bedIndex).max().orElse(0) + 1;
            for (int i = 0; i < target - current; i++) {
                Bed b = new Bed();
                b.setHospitalId(hospitalId);
                b.setWardId(ward.getWardId());
                b.setBedCode(String.format("%s-B%d", ward.getWardName(), next + i));
                b.setStatus(com.hms.entity.BedStatus.AVAILABLE);
                bedRepository.save(b);
            }
        } else if (target < current) {
            List<Bed> free = beds.stream()
                    .filter(b -> com.hms.entity.BedStatus.AVAILABLE.equalsIgnoreCase(b.getStatus()))
                    .sorted(java.util.Comparator.comparingInt(this::bedIndex).reversed())
                    .collect(Collectors.toList());

            int toRemove = current - target;
            int inUse = current - free.size();
            if (toRemove > free.size()) {
                throw new IllegalArgumentException(
                        "Cannot reduce to " + target + " bed(s): " + inUse
                                + " bed(s) are occupied or unavailable. The minimum for this ward is " + inUse + ".");
            }
            bedRepository.deleteAll(free.subList(0, toRemove));
        }

        ward.setTotalBeds(target);
    }

    /**
     * ICU Phase 2 — reclassify a ward, rejected while a bed of that ward holds a patient.
     *
     * The ICU board's core property is that an ICU bed and a critical-care patient are two
     * views of one fact. Re-typing a ward under its occupants would break that retroactively:
     * patients already lying in those beds would appear on (or vanish from) the ICU board with
     * no admission time, reason or consultant behind the change. The administrator empties the
     * ward first.
     *
     * Only OCCUPIED blocks the change — a bed awaiting cleaning or under maintenance holds no
     * patient, so reclassifying around it is safe. Setting the same type again is a no-op and
     * is never rejected.
     */
    private void applyUnitType(Ward ward, String requestedType, Long hospitalId) {
        String normalized = com.hms.service.hospital.icu.CareUnitRegistry.normalize(requestedType);
        String current = ward.getUnitType() == null
                ? com.hms.service.hospital.icu.CareUnitRegistry.GENERAL
                : ward.getUnitType();
        if (normalized.equals(current)) return;

        boolean hasOccupiedBed = bedRepository.findByWardIdAndHospitalId(ward.getWardId(), hospitalId)
                .stream()
                .anyMatch(b -> com.hms.entity.BedStatus.OCCUPIED.equalsIgnoreCase(b.getStatus()));
        if (hasOccupiedBed) {
            throw new IllegalArgumentException(
                    "Cannot change the unit type of a ward with occupied beds. "
                            + "Move or discharge its patients first.");
        }

        ward.setUnitType(normalized);
        auditLogService.logAction("WARD_UNIT_TYPE_CHANGED",
                "Ward " + ward.getWardName() + " unit type " + current + " -> " + normalized,
                securityHelper.getCurrentUserEmail(), hospitalId, "WARD",
                String.valueOf(ward.getWardId()), null);
    }

    private WardResponse toResponse(Ward w) {
        WardResponse r = new WardResponse();
        r.setWardId(w.getWardId());
        r.setWardName(w.getWardName());
        r.setBedPrice(w.getBedPrice());
        r.setTotalBeds(w.getTotalBeds());
        r.setFloorNumber(w.getFloorNumber());
        r.setInchargeNurseId(w.getInchargeNurseId());
        r.setStaffed(w.getInchargeNurseId() != null);
        String unitType = (w.getUnitType() == null || w.getUnitType().isBlank())
                ? com.hms.service.hospital.icu.CareUnitRegistry.GENERAL
                : w.getUnitType();
        r.setUnitType(unitType);
        r.setUnitTypeLabel(com.hms.service.hospital.icu.CareUnitRegistry.labelOf(unitType));
        return r;
    }
}

package com.hms.service.hospital;

import com.hms.dto.MyPatientDTO;
import com.hms.dto.NurseDashboardDTO;
import com.hms.dto.NursePatientDetailDTO;
import com.hms.entity.*;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.*;
import com.hms.security.NurseAccessGuard;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * NurseWorkspaceService - nurse-facing reads (Phase 1): dashboard aggregates,
 * "my patients" (active assignments), and the composite read-only bedside view.
 * Every patient read is gated by an active assignment via {@link NurseAccessGuard}.
 */
@Service
public class NurseWorkspaceService {

    private static final int RECENT_LIMIT = 5;

    @Autowired private PatientNurseAssignmentRepository assignmentRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private WardRepository wardRepository;
    @Autowired private BedRepository bedRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private MedicalRecordRepository medicalRecordRepository;
    @Autowired private BillingRepository billingRepository;
    @Autowired private BillingPaymentRepository billingPaymentRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseAccessGuard nurseAccessGuard;
    @Autowired private com.hms.repository.NurseProfileRepository nurseProfileRepository;
    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private com.hms.security.NurseInchargeGuard nurseInchargeGuard;
    @Autowired private com.hms.service.RealtimeNotifier notifier;
    @Autowired private NurseAssignmentService nurseAssignmentService;
    @Autowired private NurseShiftScheduleService nurseShiftScheduleService;
    @Autowired private com.hms.repository.NurseAttendanceRepository nurseAttendanceRepository;
    @Autowired private NurseCoverageService coverageService;

    private static final List<String> ACTIVE_SURGERY_STATUSES =
            List.of(Surgery.REQUESTED, Surgery.SCHEDULED, Surgery.IN_PROGRESS);

    /** Whether the current nurse is on shift NOW, derived from today's schedule. */
    public boolean getShiftStatus() {
        return nurseShiftScheduleService.isOnShiftNow(currentProfile().getId());
    }

    private com.hms.entity.NurseProfile currentProfile() {
        requireHospitalId();
        Long userId = securityHelper.getCurrentUserId();
        return nurseProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Nurse profile not found"));
    }

    public NurseDashboardDTO getDashboard() {
        List<MyPatientDTO> patients = getMyPatients();
        NurseDashboardDTO dto = new NurseDashboardDTO();
        dto.setAssignedPatientCount(patients.size());
        dto.setRecentPatients(patients.size() > RECENT_LIMIT ? patients.subList(0, RECENT_LIMIT) : patients);
        return dto;
    }

    /**
     * Active admissions assigned to the current nurse, enriched for display.
     */
    public List<MyPatientDTO> getMyPatients() {
        Long hospitalId = requireHospitalId();
        Long nurseId = securityHelper.getCurrentUserId();

        java.util.Map<Long, MyPatientDTO> byAdmission = new java.util.LinkedHashMap<>();

        // Ward visibility is the authorization boundary; primary assignment only adds ownership context.
        List<Long> wardIds = nurseInchargeGuard.myWardIds();
        if (!wardIds.isEmpty()) {
            for (IpdAdmission ipd : ipdAdmissionRepository.findByHospitalIdAndWardIdInAndStatusIn(
                    hospitalId, wardIds, List.of("ADMITTED", "DISCHARGE_PLANNED"))) {
                MyPatientDTO dto = buildMyPatient(ipd.getId(), null);
                if (dto != null) byAdmission.putIfAbsent(dto.getIpdAdmissionId(), dto);
            }
        }

        // Preserve historical assignment/substitution visibility as a defensive fallback.
        for (PatientNurseAssignment a : assignmentRepository.findByNurseUserIdAndIsActiveTrue(nurseId)) {
            if (!hospitalId.equals(a.getHospitalId())) continue; // defensive scope
            MyPatientDTO dto = buildMyPatient(a.getIpdAdmissionId(), null);
            if (dto != null) byAdmission.putIfAbsent(dto.getIpdAdmissionId(), dto);
        }

        // Plus the patients of any primary nurse this nurse is currently covering.
        for (Long primaryUserId : coverageService.coveredUserIds(nurseId, java.time.LocalDate.now())) {
            String primaryName = nurseProfileRepository.findByUserId(primaryUserId)
                    .map(com.hms.entity.NurseProfile::getName).orElse(null);
            for (PatientNurseAssignment a : assignmentRepository.findByNurseUserIdAndIsActiveTrue(primaryUserId)) {
                if (!hospitalId.equals(a.getHospitalId())) continue;
                if (byAdmission.containsKey(a.getIpdAdmissionId())) continue;
                MyPatientDTO dto = buildMyPatient(a.getIpdAdmissionId(), primaryName);
                if (dto != null) byAdmission.putIfAbsent(dto.getIpdAdmissionId(), dto);
            }
        }
        return new ArrayList<>(byAdmission.values());
    }

    /** Build the display DTO for an admission, or null if missing/discharged. */
    private MyPatientDTO buildMyPatient(Long admissionId, String coveredFor) {
        IpdAdmission ipd = ipdAdmissionRepository.findByIdAndHospitalId(admissionId, requireHospitalId()).orElse(null);
        if (ipd == null || !("ADMITTED".equalsIgnoreCase(ipd.getStatus()) || "DISCHARGE_PLANNED".equalsIgnoreCase(ipd.getStatus()))) return null;

        MyPatientDTO dto = new MyPatientDTO();
        dto.setIpdAdmissionId(ipd.getId());
        dto.setIpdNumber(ipd.getIpdNumber());
        dto.setPrimaryDiagnosis(ipd.getPrimaryDiagnosis());
        dto.setAdmissionDateTime(ipd.getAdmissionDatetime());
        dto.setStatus(ipd.getStatus());
        dto.setAdmissionConfirmed(Boolean.TRUE.equals(ipd.getAdmissionConfirmed()));
        dto.setCoveredFor(coveredFor);
        patientRepository.findById(ipd.getPatientId()).ifPresent(p -> {
            dto.setPatientName(p.getName());
            dto.setAge(p.getAge());
            dto.setGender(p.getGender());
        });
        doctorRepository.findById(ipd.getDoctorId()).ifPresent(d -> dto.setDoctorName(d.getName()));
        if (ipd.getWardId() != null) {
            wardRepository.findById(ipd.getWardId()).ifPresent(w -> dto.setWardName(w.getWardName()));
        }
        if (ipd.getBedId() != null) {
            bedRepository.findById(ipd.getBedId()).ifPresent(b -> dto.setBedCode(b.getBedCode()));
        }
        surgeryRepository.findByIpdAdmissionIdAndStatusIn(ipd.getId(), ACTIVE_SURGERY_STATUSES).stream()
                .findFirst().ifPresent(s -> dto.setSurgeryStatus(s.getStatus()));
        return dto;
    }

    /**
     * Composite read-only bedside view. 403 unless the nurse is assigned.
     */
    public NursePatientDetailDTO getPatientDetail(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        nurseAccessGuard.assertAssigned(ipdAdmissionId);
        return buildPatientDetail(ipdAdmissionId, hospitalId);
    }

    /**
     * The same bedside view for a Nurse Incharge, scoped to the wards they run.
     *
     * <p>A separate entry point rather than a widened {@link #getPatientDetail}: the staff-nurse
     * rule is "only your own patients", and an incharge is never assigned to a patient — they
     * supervise a ward. Relaxing the staff-nurse guard to let the incharge through would have
     * loosened it for staff nurses too, so the two rules stay separate and only the DTO builder
     * is shared.
     *
     * <p>{@code assertAdmissionInMyWard} is the same ward check {@link NurseWriteAccess} already
     * applies when an incharge records care, so read and write scope cannot drift apart.
     */
    public NursePatientDetailDTO getWardPatientDetail(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        nurseInchargeGuard.assertAdmissionInMyWard(ipdAdmissionId);
        return buildPatientDetail(ipdAdmissionId, hospitalId);
    }

    private NursePatientDetailDTO buildPatientDetail(Long ipdAdmissionId, Long hospitalId) {
        IpdAdmission ipd = ipdAdmissionRepository.findById(ipdAdmissionId)
                .orElseThrow(() -> new IllegalArgumentException("IPD admission not found"));
        if (!hospitalId.equals(ipd.getHospitalId())) {
            throw new UnauthorizedException("Access denied: admission belongs to another hospital");
        }

        NursePatientDetailDTO dto = new NursePatientDetailDTO();
        dto.setIpdAdmissionId(ipd.getId());
        dto.setIpdNumber(ipd.getIpdNumber());
        dto.setAdmissionType(ipd.getAdmissionType());
        dto.setStatus(ipd.getStatus());
        dto.setAdmissionDateTime(ipd.getAdmissionDatetime());
        dto.setPrimaryDiagnosis(ipd.getPrimaryDiagnosis());

        patientRepository.findById(ipd.getPatientId()).ifPresent(p -> {
            dto.setPatientId(p.getId());
            dto.setPatientPublicId(p.getPublicId());
            dto.setPatientName(p.getName());
            dto.setAge(p.getAge());
            dto.setGender(p.getGender());
            dto.setPhone(p.getPhone());
        });
        doctorRepository.findById(ipd.getDoctorId()).ifPresent(d -> dto.setDoctorName(d.getName()));
        if (ipd.getWardId() != null) {
            wardRepository.findById(ipd.getWardId()).ifPresent(w -> dto.setWardName(w.getWardName()));
        }
        if (ipd.getBedId() != null) {
            bedRepository.findById(ipd.getBedId()).ifPresent(b -> dto.setBedCode(b.getBedCode()));
        }

        // Latest doctor medical record for this admission
        List<MedicalRecord> records = medicalRecordRepository.findByIpdAdmissionIdOrderByCreatedAtDesc(ipdAdmissionId);
        if (records != null && !records.isEmpty()) {
            MedicalRecord latest = records.get(0);
            dto.setDiagnosis(latest.getDiagnosis());
            dto.setTreatmentNotes(latest.getTreatmentNotes());
            dto.setFollowUpDate(latest.getFollowUpDate());
        }

        // Current (ACTIVE) prescriptions
        List<NursePatientDetailDTO.PrescriptionLite> meds = new ArrayList<>();
        List<Prescription> active = prescriptionRepository.findByIpdAdmissionIdAndStatus(ipdAdmissionId, "ACTIVE");
        if (active != null) {
            for (Prescription pr : active) {
                NursePatientDetailDTO.PrescriptionLite lite = new NursePatientDetailDTO.PrescriptionLite();
                lite.setMedicineName(pr.getMedicineName());
                lite.setDosage(pr.getDosage());
                lite.setFrequency(pr.getFrequency());
                lite.setDuration(pr.getDuration());
                lite.setRoute(pr.getRoute());
                lite.setType(pr.getType());
                lite.setStatus(pr.getStatus());
                meds.add(lite);
            }
        }
        dto.setPrescriptions(meds);

        dto.setBilling(buildBillingSummary(ipdAdmissionId));
        return dto;
    }

    private NursePatientDetailDTO.BillingSummary buildBillingSummary(Long ipdAdmissionId) {
        NursePatientDetailDTO.BillingSummary summary = new NursePatientDetailDTO.BillingSummary();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        List<Billing> bills = billingRepository.findByIpdAdmissionId(ipdAdmissionId);
        if (bills != null) {
            for (Billing b : bills) {
                if (b.getAmount() != null) total = total.add(b.getAmount());
                paid = paid.add(sumPayments(b.getId()));
            }
        }
        summary.setTotal(total);
        summary.setPaid(paid);
        summary.setBalance(total.subtract(paid));
        return summary;
    }

    /** Sum of recorded payments for one bill; best-effort (never throws to the caller). */
    private BigDecimal sumPayments(Long billingId) {
        BigDecimal paid = BigDecimal.ZERO;
        try {
            List<BillingPayment> pays = billingPaymentRepository.findByBillingId(billingId);
            if (pays != null) {
                for (BillingPayment p : pays) {
                    if (p.getAmount() != null) paid = paid.add(p.getAmount());
                }
            }
        } catch (Exception ignored) { /* best-effort summary */ }
        return paid;
    }

    /** Patients across the incharge's wards (or all wards for admin). */
    public List<MyPatientDTO> getWardPatients() {
        List<Long> wardIds = nurseInchargeGuard.myWardIds();
        List<MyPatientDTO> out = new ArrayList<>();
        if (wardIds.isEmpty()) return out;
        for (IpdAdmission ipd : ipdAdmissionRepository.findByHospitalIdAndWardIdInAndStatusIn(
                securityHelper.getCurrentHospitalId(), wardIds, List.of("ADMITTED", "DISCHARGE_PLANNED"))) {
            if (!wardIds.contains(ipd.getWardId())) continue;
            MyPatientDTO dto = new MyPatientDTO();
            dto.setIpdAdmissionId(ipd.getId());
            dto.setIpdNumber(ipd.getIpdNumber());
            dto.setStatus(ipd.getStatus());
            dto.setWardId(ipd.getWardId());
            dto.setAdmissionDateTime(ipd.getAdmissionDatetime());
            patientRepository.findById(ipd.getPatientId()).ifPresent(p -> {
                dto.setPatientName(p.getName()); dto.setAge(p.getAge()); dto.setGender(p.getGender());
            });
            if (ipd.getWardId() != null)
                wardRepository.findById(ipd.getWardId()).ifPresent(w -> dto.setWardName(w.getWardName()));
            if (ipd.getBedId() != null)
                bedRepository.findById(ipd.getBedId()).ifPresent(b -> dto.setBedCode(b.getBedCode()));
            out.add(dto);
        }
        return out;
    }

    /** Active ward-visible admissions without a primary worklist owner. */
    public List<MyPatientDTO> getUnassignedPatients() {
        List<MyPatientDTO> unassigned = new ArrayList<>();
        for (MyPatientDTO patient : getWardPatients()) {
            if (assignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(patient.getIpdAdmissionId()).isEmpty()) {
                unassigned.add(patient);
            }
        }
        return unassigned;
    }

    @org.springframework.transaction.annotation.Transactional
    public void assignPatientNurse(Long ipdAdmissionId, Long nurseProfileId) {
        nurseInchargeGuard.assertAdmissionInMyWard(ipdAdmissionId);
        com.hms.entity.NurseProfile p = nurseProfileRepository.findById(nurseProfileId)
                .orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (!hospitalId.equals(p.getHospitalId()) || !Boolean.TRUE.equals(p.getIsActive())
                || Boolean.TRUE.equals(p.getIsIncharge()) || p.getUserId() == null) {
            throw new IllegalArgumentException("Select an active staff nurse with a login");
        }
        // Isolation: the nurse must belong to one of the caller's wards, so an
        // incharge can only assign their own nurses (admin bypasses in the guard).
        nurseInchargeGuard.assertWardAccess(p.getWardId());
        // NurseAssignmentService.assignNurse already closes any prior active
        // assignment for this admission before opening the new one.
        nurseAssignmentService.assignNurse(ipdAdmissionId, p.getUserId(), "Assigned by incharge");

        // An assignment changes two screens at once: the incharge's ward list, and the assigned
        // nurse's "My Patients". Without a push the nurse learns she has a new patient only when
        // she happens to reload.
        notifier.refresh(hospitalId);
    }

    /** Active, non-incharge staff nurses in a ward the caller may access (for the assign dropdown). */
    public List<java.util.Map<String, Object>> getWardStaffNurses(Long wardId) {
        nurseInchargeGuard.assertWardAccess(wardId);
        Long hospitalId = requireHospitalId();
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        for (com.hms.entity.NurseProfile p :
                coverageService.effectiveWardNurses(wardId, java.time.LocalDate.now())) {
            if (!hospitalId.equals(p.getHospitalId()) || p.getUserId() == null) continue;
            out.add(java.util.Map.of("id", p.getId(), "name", p.getName()));
        }
        return out;
    }

    /**
     * The incharge's nurse roster: every active staff nurse across the wards the
     * caller is incharge of (all hospital wards for admin). Because a ward has a
     * single incharge and a nurse belongs to a single ward, each nurse appears
     * under exactly one incharge — the isolation the roster relies on.
     */
    public List<java.util.Map<String, Object>> getMyNurses() {
        Long hospitalId = requireHospitalId();
        List<Long> wardIds = nurseInchargeGuard.myWardIds();
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        for (Long wardId : wardIds) {
            String wardName = wardRepository.findById(wardId)
                    .map(com.hms.entity.Ward::getWardName).orElse(null);
            for (com.hms.entity.NurseProfile p :
                    nurseProfileRepository.findByWardIdAndIsInchargeFalseAndIsActiveTrue(wardId)) {
                if (!hospitalId.equals(p.getHospitalId())) continue;
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("nurseProfileId", p.getId());
                m.put("publicId", p.getPublicId());
                m.put("name", p.getName());
                m.put("customId", p.getCustomId());
                m.put("phone", p.getPhone());
                m.put("email", p.getEmail());
                m.put("wardId", wardId);
                m.put("wardName", wardName);
                m.put("hasLogin", p.getUserId() != null);
                // Same resolver the admin's nurse list uses, so the two screens cannot disagree
                // about one nurse's shift. Best-effort: a roster problem must not blank the ward.
                try {
                    NurseShiftScheduleService.EffectiveShift shift =
                            nurseShiftScheduleService.effectiveShiftToday(p.getId());
                    m.put("shiftName", shift == null ? null : shift.shiftName());
                    m.put("shiftStartTime", shift == null ? null : String.valueOf(shift.startTime()));
                    m.put("shiftEndTime", shift == null ? null : String.valueOf(shift.endTime()));
                    m.put("onShiftNow", shift != null && shift.onShiftNow());
                } catch (Exception ignored) {
                    m.put("onShiftNow", false);
                }
                out.add(m);
            }
        }
        return out;
    }

    /** The wards the caller is incharge of (all hospital wards for admin), with their beds. */
    public List<java.util.Map<String, Object>> getMyWards() {
        List<Long> wardIds = nurseInchargeGuard.myWardIds();
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        for (Long id : wardIds) {
            wardRepository.findById(id).ifPresent(w -> {
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("wardId", w.getWardId());
                m.put("wardName", w.getWardName());
                m.put("beds", bedRepository.findByWardIdAndHospitalId(w.getWardId(), w.getHospitalId()));
                out.add(m);
            });
        }
        return out;
    }

    /** Aggregated counts for the Nurse Incharge dashboard, across the caller's wards. */
    public com.hms.dto.NurseInchargeDashboardDTO getInchargeDashboard() {
        Long hospitalId = requireHospitalId();
        java.util.Set<Long> wardIds = new java.util.HashSet<>(nurseInchargeGuard.myWardIds());
        com.hms.dto.NurseInchargeDashboardDTO dto = new com.hms.dto.NurseInchargeDashboardDTO();
        if (wardIds.isEmpty()) return dto;

        java.time.LocalDate today = java.time.LocalDate.now();

        // Patients
        for (IpdAdmission ipd : ipdAdmissionRepository.findByHospitalIdAndStatusIn(hospitalId,
                java.util.List.of("ADMITTED", "DISCHARGED"))) {
            if (ipd.getWardId() == null || !wardIds.contains(ipd.getWardId())) continue;
            if ("ADMITTED".equalsIgnoreCase(ipd.getStatus())) {
                dto.getPatients().setTotal(dto.getPatients().getTotal() + 1);
            }
            if (ipd.getAdmissionDatetime() != null && today.equals(ipd.getAdmissionDatetime().toLocalDate())) {
                dto.getPatients().setNewAdmissionsToday(dto.getPatients().getNewAdmissionsToday() + 1);
            }
            if ("DISCHARGED".equalsIgnoreCase(ipd.getStatus()) && ipd.getDischargeDatetime() != null
                    && today.equals(ipd.getDischargeDatetime().toLocalDate())) {
                dto.getPatients().setDischargesToday(dto.getPatients().getDischargesToday() + 1);
            }
        }

        // Nurses (active, non-incharge, in my wards) + today's attendance
        for (Long wardId : wardIds) {
            dto.getNurses().setTotal(dto.getNurses().getTotal()
                    + coverageService.effectiveWardNurses(wardId, today).size());
            for (com.hms.entity.NurseAttendance a : nurseAttendanceRepository.findByWardIdAndAttendanceDate(wardId, today)) {
                switch (a.getStatus() == null ? "" : a.getStatus()) {
                    case "PRESENT", "LATE", "HALF_DAY" -> dto.getNurses().setPresent(dto.getNurses().getPresent() + 1);
                    case "ABSENT" -> dto.getNurses().setAbsent(dto.getNurses().getAbsent() + 1);
                    case "LEAVE" -> dto.getNurses().setOnLeave(dto.getNurses().getOnLeave() + 1);
                    default -> { }
                }
            }
        }

        // Beds
        for (Long wardId : wardIds) {
            for (com.hms.entity.Bed b : bedRepository.findByWardIdAndHospitalId(wardId, hospitalId)) {
                dto.getBeds().setTotal(dto.getBeds().getTotal() + 1);
                switch (b.getStatus() == null ? "" : b.getStatus()) {
                    case com.hms.entity.BedStatus.AVAILABLE -> dto.getBeds().setAvailable(dto.getBeds().getAvailable() + 1);
                    case com.hms.entity.BedStatus.OCCUPIED -> dto.getBeds().setOccupied(dto.getBeds().getOccupied() + 1);
                    case com.hms.entity.BedStatus.CLEANING -> dto.getBeds().setCleaningRequired(dto.getBeds().getCleaningRequired() + 1);
                    case com.hms.entity.BedStatus.MAINTENANCE -> dto.getBeds().setUnderMaintenance(dto.getBeds().getUnderMaintenance() + 1);
                    default -> { }
                }
            }
        }
        return dto;
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        return hospitalId;
    }
}

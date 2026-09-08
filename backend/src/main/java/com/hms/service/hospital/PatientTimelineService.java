package com.hms.service.hospital;

import com.hms.dto.TimelineEventDTO;
import com.hms.entity.DischargeSummary;
import com.hms.entity.IpdAdmission;
import com.hms.entity.IpdBedHistory;
import com.hms.entity.MedicalRecord;
import com.hms.entity.MedicationAdministration;
import com.hms.entity.NursingNote;
import com.hms.entity.Opd;
import com.hms.entity.PatientDocument;
import com.hms.entity.PatientNurseAssignment;
import com.hms.entity.Prescription;
import com.hms.entity.RecoveryEpisode;
import com.hms.entity.RecoveryObservation;
import com.hms.entity.SugarChartEntry;
import com.hms.entity.Surgery;
import com.hms.entity.SurgeryAnaesthesiaClearance;
import com.hms.entity.SurgeryForm;
import com.hms.entity.SurgeryMilestone;
import com.hms.entity.SurgeryStateTransition;
import com.hms.entity.SurgeryTeamMember;
import com.hms.entity.User;
import com.hms.entity.VitalsRecord;
import com.hms.entity.WhoChecklist;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.BedRepository;
import com.hms.repository.DischargeSummaryRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.IpdBedHistoryRepository;
import com.hms.repository.MedicalRecordRepository;
import com.hms.repository.MedicationAdministrationRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.NursingNoteRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientDocumentRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.PrescriptionRepository;
import com.hms.repository.RecoveryEpisodeRepository;
import com.hms.repository.RecoveryObservationRepository;
import com.hms.repository.SugarChartEntryRepository;
import com.hms.repository.SurgeryAnaesthesiaClearanceRepository;
import com.hms.repository.SurgeryFormRepository;
import com.hms.repository.SurgeryMilestoneRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.repository.SurgeryStateTransitionRepository;
import com.hms.repository.SurgeryTeamMemberRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.VitalsRecordRepository;
import com.hms.repository.WardRepository;
import com.hms.repository.WhoChecklistRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PatientTimelineService - one read model that aggregates a patient's clinical history across
 * OPD, IPD, Nursing, OT and Recovery into a single chronological view (CLIN-P1).
 *
 * This is a read model, not a new source of truth: every event below is built from an already
 * authoritative entity and carries a (sourceType, sourceId) pointer back to it. No clinical
 * content is copied into a new table; nothing here is ever written back to the entities it
 * reads. Every query is scoped to the caller's hospital and the requested patient -- OPD is
 * scoped through the owning patient (it has no hospital_id of its own), matching
 * OpdRepositoryScopingArchTest's rule.
 *
 * Deliberately excluded for this release: WHO checklist site-marking/counts-correct booleans (no
 * standalone timestamp beyond the three phase timestamps already surfaced), lab/radiology results
 * (verified during the architecture pass to have no persisted entity yet), and billing (financial,
 * not clinical -- out of scope for a *clinical* timeline).
 */
@Service
public class PatientTimelineService {
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private PatientRepository patientRepository;
    @Autowired private OpdRepository opdRepository;
    @Autowired private MedicalRecordRepository medicalRecordRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private IpdBedHistoryRepository ipdBedHistoryRepository;
    @Autowired private PatientNurseAssignmentRepository patientNurseAssignmentRepository;
    @Autowired private PatientDocumentRepository patientDocumentRepository;
    @Autowired private VitalsRecordRepository vitalsRecordRepository;
    @Autowired private NursingNoteRepository nursingNoteRepository;
    @Autowired private SugarChartEntryRepository sugarChartEntryRepository;
    @Autowired private MedicationAdministrationRepository medicationAdministrationRepository;
    @Autowired private DischargeSummaryRepository dischargeSummaryRepository;
    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private SurgeryStateTransitionRepository surgeryStateTransitionRepository;
    @Autowired private SurgeryMilestoneRepository surgeryMilestoneRepository;
    @Autowired private SurgeryTeamMemberRepository surgeryTeamMemberRepository;
    @Autowired private SurgeryFormRepository surgeryFormRepository;
    @Autowired private SurgeryAnaesthesiaClearanceRepository anaesthesiaClearanceRepository;
    @Autowired private WhoChecklistRepository whoChecklistRepository;
    @Autowired private RecoveryEpisodeRepository recoveryEpisodeRepository;
    @Autowired private RecoveryObservationRepository recoveryObservationRepository;
    @Autowired private WardRepository wardRepository;
    @Autowired private BedRepository bedRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;

    public List<TimelineEventDTO> forPatient(Long patientId) {
        Long hospitalId = requireHospitalId();
        // Tenant + patient scope proven once, up front; every subsequent read is keyed off ids
        // already known to belong to this patient at this hospital.
        patientRepository.findByIdAndHospitalIdAndIsActiveTrue(patientId, hospitalId)
                .orElseThrow(() -> new IllegalArgumentException("Patient not found"));

        List<TimelineEventDTO> events = new ArrayList<>();
        Set<Long> userIds = new HashSet<>();

        // ---- OPD ----
        List<Opd> opds = opdRepository.findByPatientAndHospitalIdOrderByCreatedAtAsc(patientId, hospitalId);
        for (Opd o : opds) {
            events.add(event(o.getCreatedAt(), "OPD_REGISTERED", "OPD", o.getId(),
                    "OPD registered" + (o.getCaseId() != null ? " (" + o.getCaseId() + ")" : ""),
                    "Opd", o.getId(), null, userIds));
        }

        // ---- Doctor consultation / diagnosis (OPD and IPD both flow through MedicalRecord) ----
        List<MedicalRecord> records = medicalRecordRepository.findByPatientIdOrderByCreatedAtDesc(patientId);
        List<Long> medicalRecordIds = new ArrayList<>();
        for (MedicalRecord r : records) {
            medicalRecordIds.add(r.getId());
            String encounterType = r.getIpdAdmissionId() != null ? "IPD" : "OPD";
            Long encounterId = r.getIpdAdmissionId() != null ? r.getIpdAdmissionId() : r.getOpdId();
            events.add(event(r.getCreatedAt(), "CONSULTATION", encounterType, encounterId,
                    "Doctor consultation" + (r.getDiagnosis() != null && !r.getDiagnosis().isBlank()
                            ? ": " + r.getDiagnosis() : ""),
                    "MedicalRecord", r.getId(), r.getDoctorId(), userIds));
        }
        // Prescriptions link to MedicalRecord, not directly to OPD/IPD -- confirmed during the
        // architecture pass. One query for every prescription across every consultation.
        if (!medicalRecordIds.isEmpty()) {
            for (Prescription p : prescriptionRepository.findByMedicalRecordIdIn(medicalRecordIds)) {
                events.add(event(p.getCreatedAt(), "PRESCRIPTION", null, null,
                        "Prescribed " + p.getMedicineName()
                                + (p.getDosage() != null ? " (" + p.getDosage() + ")" : ""),
                        "Prescription", p.getId(), null, userIds));
            }
        }

        // ---- IPD admissions ----
        List<IpdAdmission> admissions = ipdAdmissionRepository.findByPatientIdOrderByAdmissionDatetimeDesc(patientId);
        List<Long> admissionIds = new ArrayList<>();
        for (IpdAdmission a : admissions) {
            if (!hospitalId.equals(a.getHospitalId())) continue; // defensive: patient-scoped, not yet hospital-filtered
            admissionIds.add(a.getId());
            events.add(event(a.getAdmissionDatetime(), "IPD_ADMITTED", "IPD", a.getId(),
                    "Admitted — " + wardName(a.getWardId()) + " / " + bedCode(a.getBedId()),
                    "IpdAdmission", a.getId(), a.getAdmittedByUserId(), userIds));
            if (a.getDischargeDatetime() != null) {
                events.add(event(a.getDischargeDatetime(), "DISCHARGED", "IPD", a.getId(),
                        "Discharged", "IpdAdmission", a.getId(), null, userIds));
            }
        }

        if (!admissionIds.isEmpty()) {
            // Ward/bed transfers.
            for (IpdBedHistory h : ipdBedHistoryRepository.findByIpdAdmissionIdInOrderByAssignedAtAsc(admissionIds)) {
                events.add(event(h.getAssignedAt(), "WARD_TRANSFER", "IPD", h.getIpdAdmissionId(),
                        "Moved to " + wardName(h.getWardId()) + " / " + bedCode(h.getBedId()),
                        "IpdBedHistory", h.getId(), null, userIds));
            }
            // Nurse assignment.
            for (PatientNurseAssignment n :
                    patientNurseAssignmentRepository.findByIpdAdmissionIdInOrderByAssignedAtAsc(admissionIds)) {
                events.add(event(n.getAssignedAt(), "NURSE_ASSIGNED", "IPD", n.getIpdAdmissionId(),
                        "Nurse assigned", "PatientNurseAssignment", n.getId(), n.getNurseUserId(), userIds));
            }
            // Vitals.
            for (VitalsRecord v :
                    vitalsRecordRepository.findByIpdAdmissionIdInAndIsActiveTrueOrderByRecordedAtAsc(admissionIds)) {
                // Pain is already part of the vitals record; naming it on the timeline entry is
                // what makes it findable longitudinally instead of only inside one form. Absent
                // and zero are different clinical statements and are worded differently.
                String summary = "Vitals recorded";
                if (v.getPainScore() != null) {
                    summary += v.getPainScore() == 0 ? " · no pain" : " · pain " + v.getPainScore() + "/10";
                }
                events.add(event(v.getRecordedAt(), "VITALS", "IPD", v.getIpdAdmissionId(),
                        summary, "VitalsRecord", v.getId(),
                        performerUserId(v.getRecordedByUserId(), v.getPerformedByNurseId()), userIds));
            }
            // Nursing notes (also carries the Re-Assessment / Initial Assessment / Vulnerability
            // Assessment forms, which are stored as categorised notes in this codebase).
            for (NursingNote n :
                    nursingNoteRepository.findByIpdAdmissionIdInAndIsActiveTrueOrderByRecordedAtAsc(admissionIds)) {
                String label = n.getCategory() != null ? n.getCategory() : "Nursing note";
                events.add(event(n.getRecordedAt(), "NURSING_NOTE", "IPD", n.getIpdAdmissionId(),
                        label, "NursingNote", n.getId(),
                        performerUserId(n.getNurseUserId(), n.getPerformedByNurseId()), userIds));
            }
            // Sugar chart.
            for (SugarChartEntry s :
                    sugarChartEntryRepository.findByIpdAdmissionIdInAndIsActiveTrueOrderByRecordedAtAsc(admissionIds)) {
                events.add(event(s.getRecordedAt(), "SUGAR_CHART", "IPD", s.getIpdAdmissionId(),
                        "Blood sugar " + s.getBloodSugar(), "SugarChartEntry", s.getId(),
                        performerUserId(s.getNurseUserId(), s.getPerformedByNurseId()), userIds));
            }
            // Medication administration.
            for (MedicationAdministration m : medicationAdministrationRepository
                    .findByIpdAdmissionIdInAndIsActiveTrueOrderByCreatedAtAsc(admissionIds)) {
                LocalDateTime at = m.getAdministeredTime() != null ? m.getAdministeredTime() : m.getCreatedAt();
                events.add(event(at, "MEDICATION_ADMINISTERED", "IPD", m.getIpdAdmissionId(),
                        "Medication administered" + (m.getStatus() != null ? " (" + m.getStatus() + ")" : ""),
                        "MedicationAdministration", m.getId(),
                        performerUserId(m.getNurseUserId(), m.getPerformedByNurseId()), userIds));
            }
            // Discharge summary (small N: one lookup per admission, not worth a bulk finder).
            for (Long admissionId : admissionIds) {
                dischargeSummaryRepository.findByIpdAdmissionId(admissionId).ifPresent(d ->
                        events.add(event(d.getCreatedAt(), "DISCHARGE_SUMMARY", "IPD", admissionId,
                                "Discharge summary" + (d.getFinalDiagnosis() != null
                                        ? ": " + d.getFinalDiagnosis() : ""),
                                "DischargeSummary", d.getId(), null, userIds)));
            }
        }

        // ---- Surgery / OT / Recovery ----
        for (Surgery s : surgeryRepository.findByPatientIdAndHospitalId(patientId, hospitalId)) {
            Long sid = s.getId();
            for (SurgeryStateTransition t : surgeryStateTransitionRepository.findBySurgeryIdOrderByCreatedAtAsc(sid)) {
                events.add(event(t.getCreatedAt(), "SURGERY_" + t.getToStatus(), "SURGERY", sid,
                        "Surgery " + t.getToStatus().toLowerCase().replace('_', ' ')
                                + (s.getProcedureName() != null ? " — " + s.getProcedureName() : ""),
                        "SurgeryStateTransition", t.getId(), t.getActorUserId(), userIds));
            }
            for (SurgeryTeamMember m : surgeryTeamMemberRepository.findBySurgeryIdOrderByIdAsc(sid)) {
                String who = m.getExternalName() != null ? m.getExternalName() : "team member";
                events.add(event(m.getCreatedAt(), "SURGICAL_TEAM_ASSIGNED", "SURGERY", sid,
                        "Surgical team: " + m.getCaseRoleCode() + " — " + who,
                        "SurgeryTeamMember", m.getId(), m.getUserId(), userIds));
            }
            anaesthesiaClearanceRepository
                    .findTopByHospitalIdAndSurgeryIdOrderByRecordedAtDescIdDesc(hospitalId, sid)
                    .ifPresent(c -> events.add(event(c.getRecordedAt(), "ANAESTHESIA_CLEARANCE", "SURGERY", sid,
                            "Anaesthesia clearance: " + c.getOutcome(),
                            "SurgeryAnaesthesiaClearance", c.getId(), c.getRecordedByUserId(), userIds)));
            whoChecklistRepository.findBySurgeryId(sid).ifPresent(w -> {
                if (w.getSignInAt() != null) {
                    events.add(event(w.getSignInAt(), "WHO_SIGN_IN", "SURGERY", sid, "WHO Sign-In",
                            "WhoChecklist", w.getId(), w.getSignInByUserId(), userIds));
                }
                if (w.getTimeOutAt() != null) {
                    events.add(event(w.getTimeOutAt(), "WHO_TIME_OUT", "SURGERY", sid, "WHO Time-Out",
                            "WhoChecklist", w.getId(), w.getTimeOutByUserId(), userIds));
                }
                if (w.getSignOutAt() != null) {
                    events.add(event(w.getSignOutAt(), "WHO_SIGN_OUT", "SURGERY", sid, "WHO Sign-Out",
                            "WhoChecklist", w.getId(), w.getSignOutByUserId(), userIds));
                }
            });
            for (SurgeryMilestone m : surgeryMilestoneRepository.findBySurgeryIdOrderByOccurredAtAsc(sid)) {
                events.add(event(m.getOccurredAt(), "INTRA_OP_MILESTONE", "SURGERY", sid,
                        m.getMilestone() != null ? m.getMilestone().replace('_', ' ') : "Milestone",
                        "SurgeryMilestone", m.getId(),
                        performerUserId(m.getRecordedByUserId(), m.getPerformedByNurseId()), userIds));
            }
            for (SurgeryForm f : surgeryFormRepository.findBySurgeryIdAndIsCurrentTrue(sid)) {
                if (f.getSignedAt() != null) {
                    events.add(event(f.getSignedAt(), "SURGERY_FORM_SIGNED", "SURGERY", sid,
                            "Form signed: " + f.getFormType(), "SurgeryForm", f.getId(), null, userIds));
                }
            }
            recoveryEpisodeRepository.findBySurgeryId(sid).ifPresent(ep -> {
                events.add(event(ep.getArrivedAt(), "RECOVERY_ADMITTED", "SURGERY", sid,
                        "Entered recovery", "RecoveryEpisode", ep.getId(), ep.getArrivedByUserId(), userIds));
                for (RecoveryObservation ob :
                        recoveryObservationRepository.findByEpisodeIdOrderByObservedAtAsc(ep.getId())) {
                    events.add(event(ob.getObservedAt(), "RECOVERY_OBSERVATION", "SURGERY", sid,
                            "Recovery vitals" + (ob.getAldreteScore() != null
                                    ? " (Aldrete " + ob.getAldreteScore() + ")" : ""),
                            "RecoveryObservation", ob.getId(),
                            performerUserId(ob.getRecordedByUserId(), ob.getPerformedByNurseId()), userIds));
                }
                if (ep.getDischargedAt() != null) {
                    events.add(event(ep.getDischargedAt(), "RECOVERY_DISCHARGED", "SURGERY", sid,
                            "Recovery discharged" + (ep.getTransferDestination() != null
                                    ? " to " + ep.getTransferDestination() : ""),
                            "RecoveryEpisode", ep.getId(), ep.getDischargedByUserId(), userIds));
                }
            });
        }

        // ---- Documents the patient brought in ----
        // Read from the document rows like every other event here: no ledger of its own, and
        // nothing about where a file lives -- a storage key is not clinical history and would be
        // a second way to name a file, which is the beginning of a way around the first.
        for (PatientDocument d : patientDocumentRepository
                .findByHospitalIdAndPatientIdOrderByIdAsc(hospitalId, patientId)) {
            String encounterType = d.getIpdAdmissionId() != null ? "IPD"
                    : (d.getOpdId() != null ? "OPD" : null);
            Long encounterId = d.getIpdAdmissionId() != null ? d.getIpdAdmissionId() : d.getOpdId();

            StringBuilder summary = new StringBuilder("Document filed: ");
            summary.append(d.getTitle() == null || d.getTitle().isBlank() ? "untitled" : d.getTitle());
            if (d.getDocumentType() != null) summary.append(" (").append(d.getDocumentType()).append(")");
            if (d.getReportDate() != null) summary.append(", reported ").append(d.getReportDate());
            if (d.getSource() != null && !d.getSource().isBlank()) {
                summary.append(", from ").append(d.getSource());
            }
            events.add(event(d.getCreatedAt(), "DOCUMENT_UPLOADED", encounterType, encounterId,
                    summary.toString(), "PatientDocument", d.getId(), d.getUploadedByUserId(), userIds));

            // Archiving is a later event, not an erasure: the filing above stays on the timeline
            // because it happened.
            if (Boolean.FALSE.equals(d.getIsActive()) && d.getArchivedAt() != null) {
                String archived = "Document archived: "
                        + (d.getTitle() == null || d.getTitle().isBlank() ? "untitled" : d.getTitle())
                        + (d.getArchiveReason() != null && !d.getArchiveReason().isBlank()
                                ? " -- " + d.getArchiveReason() : "");
                events.add(event(d.getArchivedAt(), "DOCUMENT_ARCHIVED", encounterType, encounterId,
                        archived, "PatientDocument", d.getId(), d.getArchivedByUserId(), userIds));
            }
        }

        resolvePerformers(events, userIds);
        events.sort(Comparator.comparing(TimelineEventDTO::getTimestamp,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }

    /** A record may attribute care to either a logged-in user or a nurse profile (no login);
     *  the profile's linked user id, if any, is what we can resolve to a name/role. */
    private Long performerUserId(Long userId, Long performedByNurseProfileId) {
        if (userId != null) return userId;
        if (performedByNurseProfileId == null) return null;
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return nurseProfileRepository.findByIdAndHospitalId(performedByNurseProfileId, hospitalId)
                .map(com.hms.entity.NurseProfile::getUserId).orElse(null);
    }

    private void resolvePerformers(List<TimelineEventDTO> events, Set<Long> userIds) {
        if (userIds.isEmpty()) return;
        Map<Long, User> byId = new HashMap<>();
        for (User u : userRepository.findAllById(userIds)) byId.put(u.getId(), u);
        for (TimelineEventDTO e : events) {
            User u = e.getPerformedByUserId() == null ? null : byId.get(e.getPerformedByUserId());
            if (u != null) {
                e.setPerformedByName(u.getName());
                e.setPerformedByRole(u.getRole());
            }
        }
    }

    private TimelineEventDTO event(LocalDateTime at, String type, String encounterType, Long encounterId,
            String summary, String sourceType, Long sourceId, Long performedByUserId, Set<Long> userIds) {
        TimelineEventDTO e = new TimelineEventDTO();
        e.setTimestamp(at);
        e.setEventType(type);
        e.setEncounterType(encounterType);
        e.setEncounterId(encounterId);
        e.setSummary(summary);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setPerformedByUserId(performedByUserId);
        if (performedByUserId != null) userIds.add(performedByUserId);
        return e;
    }

    private String wardName(Long wardId) {
        if (wardId == null) return "Ward";
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return wardRepository.findByWardIdAndHospitalId(wardId, hospitalId).map(w -> w.getWardName()).orElse("Ward");
    }

    private String bedCode(Long bedId) {
        if (bedId == null) return "Bed";
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return bedRepository.findByBedIdAndHospitalId(bedId, hospitalId).map(b -> b.getBedCode()).orElse("Bed");
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }
}

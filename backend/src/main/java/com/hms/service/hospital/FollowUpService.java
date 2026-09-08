package com.hms.service.hospital;

import com.hms.dto.FollowUpDTO;
import com.hms.exception.UnauthorizedException;
import com.hms.dto.CreateOpdRequest;
import com.hms.entity.MedicalRecord;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.exception.ConflictException;
import com.hms.exception.ResourceNotFoundException;
import com.hms.repository.DoctorRepository;
import com.hms.repository.MedicalRecordRepository;
import com.hms.repository.PatientRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Reads outstanding follow-ups. Writes nothing, ever.
 *
 * <p>That is the point of this class. Before it, the only way a due follow-up became visible was
 * a side effect of opening the OPD queue, which created an OPD, a queue entry and an audit row —
 * so a date arriving was indistinguishable from a patient walking in, and reading a screen twice
 * could produce two encounters for one person. Being due is now a question about stored data, and
 * asking it changes nothing.
 *
 * <p>OVERDUE, DUE_TODAY and UPCOMING are derived here from the date rather than stored, so no job
 * has to rewrite anything at midnight and an outage cannot leave a patient in the wrong bucket.
 */
@Service
public class FollowUpService {

    /** Past the date and still open. Stays here until somebody actually deals with it. */
    public static final String OVERDUE = "OVERDUE";
    public static final String DUE_TODAY = "DUE_TODAY";
    public static final String UPCOMING = "UPCOMING";

    /**
     * How far ahead "upcoming" looks. A window rather than everything, because a list that also
     * contains next year's appointments is not a worklist.
     */
    private static final int UPCOMING_WINDOW_DAYS = 30;

    /**
     * How far back overdue looks by default. Follow-ups written before this feature existed have
     * no status, so they are all open — without a bound, a facility's first view would be years
     * of history. The date is never altered; this only decides what today's list shows, and a
     * caller that wants the whole tail can ask for it.
     */
    private static final int OVERDUE_WINDOW_DAYS = 90;

    @Autowired private MedicalRecordRepository medicalRecordRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private BusinessClock clock;
    @Autowired private OpdService opdService;
    @Autowired private PatientRepository patientRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private com.hms.service.AuditLogService auditLogService;

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }

    /**
     * Everything outstanding a facility should be looking at: overdue, due today, and the next
     * few weeks — in one query, bucketed here.
     *
     * @param doctorId optional; when given, only that doctor's own follow-ups
     * @param overdueDays how far back to look, or null for the default window
     */
    @Transactional(readOnly = true)
    public List<FollowUpDTO> outstanding(Long doctorId, Integer overdueDays) {
        Long hospitalId = requireHospitalId();
        LocalDate today = clock.today();
        int back = overdueDays != null && overdueDays >= 0 ? overdueDays : OVERDUE_WINDOW_DAYS;
        LocalDate from = today.minusDays(back);
        LocalDate to = today.plusDays(UPCOMING_WINDOW_DAYS);

        List<FollowUpDTO> rows = doctorId == null
                ? medicalRecordRepository.findOpenFollowUpsBetween(hospitalId, from, to)
                : medicalRecordRepository.findOpenFollowUpsBetweenForDoctor(hospitalId, doctorId, from, to);

        for (FollowUpDTO row : rows) {
            row.setTiming(timingOf(row.getFollowUpDate(), today));
            row.setDaysOverdue(ChronoUnit.DAYS.between(row.getFollowUpDate(), today));
        }
        return rows;
    }

    /** One bucket only, for a caller that wants exactly one list. */
    @Transactional(readOnly = true)
    public List<FollowUpDTO> outstanding(String timing, Long doctorId, Integer overdueDays) {
        if (timing == null || timing.isBlank()) return outstanding(doctorId, overdueDays);
        String wanted = timing.trim().toUpperCase(java.util.Locale.ROOT);
        if (!OVERDUE.equals(wanted) && !DUE_TODAY.equals(wanted) && !UPCOMING.equals(wanted)) {
            throw new IllegalArgumentException(
                    "Unknown follow-up bucket: " + timing + ". Expected OVERDUE, DUE_TODAY or UPCOMING.");
        }
        return outstanding(doctorId, overdueDays).stream()
                .filter(r -> wanted.equals(r.getTiming()))
                .toList();
    }

    static String timingOf(LocalDate followUpDate, LocalDate today) {
        if (followUpDate.isBefore(today)) return OVERDUE;
        if (followUpDate.isEqual(today)) return DUE_TODAY;
        return UPCOMING;
    }

    // ── the patient came back ────────────────────────────────────────────────

    /**
     * Records that a patient returned for a follow-up, and turns it into a real visit.
     *
     * <p>One transaction. The OPD is created through the ordinary {@link OpdService#createOpd}
     * path, so it inherits that path's tenant checks, patient and doctor validation, queue entry,
     * billing and audit rather than a second implementation of them. The follow-up is then
     * claimed with a conditional UPDATE; if that claim loses — another receptionist got there
     * first, or a double-click — the whole thing rolls back and the OPD, its queue entry and its
     * bill go with it. There is no state in which the follow-up is actioned but the visit is
     * missing, or the visit exists while the follow-up still looks outstanding.
     *
     * @param medicalRecordId the follow-up being actioned
     * @param problem         optional presenting complaint; the original diagnosis when omitted
     */
    @Transactional
    public Opd recordArrival(Long medicalRecordId, String problem) {
        Long hospitalId = requireHospitalId();

        // Another facility's follow-up is indistinguishable from one that does not exist.
        MedicalRecord record = medicalRecordRepository
                .findByIdAndHospitalId(medicalRecordId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Follow-up not found"));

        assertActionable(record);

        Patient patient = patientRepository
                .findByIdAndHospitalIdAndIsActiveTrue(record.getPatientId(), hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        CreateOpdRequest req = new CreateOpdRequest();
        req.setPatientId(String.valueOf(patient.getId()));
        req.setVisitType(Opd.VisitType.FOLLOWUP.name());
        req.setProblem(problem != null && !problem.isBlank()
                ? problem.trim()
                : (record.getDiagnosis() != null ? "Follow-up: " + record.getDiagnosis() : "Follow-up"));

        // The doctor who asked for the follow-up sees the patient again. Left unset if that
        // doctor has since left, so createOpd applies whatever it does for an unassigned visit
        // rather than this code inventing a rule of its own.
        if (record.getDoctorId() != null) {
            doctorRepository.findByIdAndHospitalIdAndIsActiveTrue(record.getDoctorId(), hospitalId)
                    .ifPresent(d -> req.setDoctorId(String.valueOf(d.getId())));
        }

        Opd opd = opdService.createOpd(req);

        int claimed = medicalRecordRepository.claimForArrival(
                record.getId(), hospitalId, opd.getId(),
                securityHelper.getCurrentUserId(), java.time.LocalDateTime.now(), clock.today());
        if (claimed != 1) {
            // Lost the race, or the follow-up was closed between the check above and here.
            // Throwing rolls back the OPD created moments ago along with everything it caused.
            throw new ConflictException(
                    "This follow-up has already been actioned. Refresh to see the current visit.");
        }

        try {
            auditLogService.logAction(
                    "FOLLOW_UP_ACTIONED",
                    "Patient arrived for follow-up; OPD " + opd.getCaseId()
                            + " created from consultation " + record.getPublicId(),
                    securityHelper.getCurrentUserEmail(), hospitalId,
                    "MEDICAL_RECORD", String.valueOf(record.getId()), null);
        } catch (Exception e) {
            // Best-effort, as everywhere else: a missing audit line must not undo a real visit.
        }

        return opd;
    }

    /** Why a follow-up may not be actioned, said plainly enough to show a receptionist. */
    private void assertActionable(MedicalRecord record) {
        if (record.getFollowUpDate() == null) {
            throw new IllegalArgumentException("This consultation has no follow-up scheduled.");
        }
        String status = record.getFollowUpStatus();
        if (record.getActionedOpdId() != null
                || MedicalRecord.FOLLOW_UP_ACTIONED.equals(status)) {
            throw new ConflictException(
                    "This follow-up has already been actioned. Refresh to see the current visit.");
        }
        if (MedicalRecord.FOLLOW_UP_COMPLETED.equals(status)) {
            throw new ConflictException("This follow-up is already completed.");
        }
        if (MedicalRecord.FOLLOW_UP_CANCELLED.equals(status)) {
            throw new ConflictException("This follow-up was cancelled.");
        }
        // Early arrivals are refused rather than quietly allowed: booking a visit against a
        // future follow-up would take it off the due list before its date, and nothing else
        // would ever bring it back.
        if (record.getFollowUpDate().isAfter(clock.today())) {
            throw new IllegalArgumentException(
                    "This follow-up is not due until " + record.getFollowUpDate() + ".");
        }
    }

    // ── the transitions that do not involve a visit ──────────────────────────

    /**
     * Moves an outstanding follow-up to a new date. It stays open.
     *
     * <p>There is no RESCHEDULED state, deliberately: the appointment is still outstanding, and a
     * terminal state would say otherwise. What changed is recorded in the audit trail, which
     * keeps both the old date and the new one — the row itself only ever holds the current
     * answer, and the history of a clinical instruction belongs somewhere durable.
     */
    @Transactional
    public void reschedule(Long medicalRecordId, LocalDate newDate, String instructions, String reason) {
        Long hospitalId = requireHospitalId();
        MedicalRecord record = requireOpenFollowUp(medicalRecordId, hospitalId);

        if (newDate == null) {
            throw new IllegalArgumentException("A new follow-up date is required.");
        }
        // Backwards has no workflow: it would land the patient straight into overdue for a
        // decision just taken. Today is allowed — "come back this afternoon" is ordinary.
        if (newDate.isBefore(clock.today())) {
            throw new IllegalArgumentException("A follow-up cannot be moved into the past.");
        }

        LocalDate previous = record.getFollowUpDate();
        int moved = medicalRecordRepository.rescheduleIfOpen(
                record.getId(), hospitalId, newDate, trimmedOrNull(instructions));
        if (moved != 1) {
            throw new ConflictException(lostRaceMessage());
        }

        audit("FOLLOW_UP_RESCHEDULED",
                "Follow-up moved from " + previous + " to " + newDate
                        + (instructions != null && !instructions.isBlank() ? "; instructions updated" : ""),
                hospitalId, record, reason);
    }

    /**
     * Closes a follow-up that no longer needs a visit — the patient improved, was seen
     * elsewhere, or the question was answered by phone.
     *
     * <p>Not the same as an arrival: nothing clinical is created, no queue entry, no bill. The
     * date and instructions stay exactly as the doctor wrote them.
     */
    @Transactional
    public void complete(Long medicalRecordId, String reason) {
        close(medicalRecordId, MedicalRecord.FOLLOW_UP_COMPLETED, "FOLLOW_UP_COMPLETED",
                "Follow-up closed without a return visit", reason);
    }

    /** Calls off a follow-up. A reason is required — a cancelled clinical instruction needs one. */
    @Transactional
    public void cancel(Long medicalRecordId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to cancel a follow-up.");
        }
        if (reason.trim().length() > 500) {
            throw new IllegalArgumentException("The cancellation reason is too long.");
        }
        close(medicalRecordId, MedicalRecord.FOLLOW_UP_CANCELLED, "FOLLOW_UP_CANCELLED",
                "Follow-up cancelled", reason);
    }

    private void close(Long medicalRecordId, String status, String action, String detail, String reason) {
        Long hospitalId = requireHospitalId();
        MedicalRecord record = requireOpenFollowUp(medicalRecordId, hospitalId);

        int closed = medicalRecordRepository.closeIfOpen(record.getId(), hospitalId, status);
        if (closed != 1) {
            throw new ConflictException(lostRaceMessage());
        }

        audit(action, detail + " (was due " + record.getFollowUpDate() + ")", hospitalId, record, reason);
    }

    /**
     * The record, if it is this facility's and still open.
     *
     * <p>This is a courtesy check that produces a readable refusal; it is not the safety
     * mechanism. Every transition re-checks the same conditions inside its own UPDATE, which is
     * what actually holds under concurrency.
     */
    private MedicalRecord requireOpenFollowUp(Long medicalRecordId, Long hospitalId) {
        MedicalRecord record = medicalRecordRepository
                .findByIdAndHospitalId(medicalRecordId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Follow-up not found"));

        if (record.getFollowUpDate() == null) {
            throw new IllegalArgumentException("This consultation has no follow-up scheduled.");
        }
        String status = record.getFollowUpStatus();
        if (record.getActionedOpdId() != null || MedicalRecord.FOLLOW_UP_ACTIONED.equals(status)) {
            throw new ConflictException(
                    "This follow-up has already been actioned and cannot be changed.");
        }
        if (MedicalRecord.FOLLOW_UP_COMPLETED.equals(status)) {
            throw new ConflictException("This follow-up is already completed.");
        }
        if (MedicalRecord.FOLLOW_UP_CANCELLED.equals(status)) {
            throw new ConflictException("This follow-up was cancelled.");
        }
        return record;
    }

    private static String lostRaceMessage() {
        return "This follow-up was changed by someone else. Refresh to see its current state.";
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Best-effort, as everywhere else here: a missing audit line must not undo a real decision. */
    private void audit(String action, String details, Long hospitalId, MedicalRecord record, String reason) {
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(),
                    hospitalId, "MEDICAL_RECORD", String.valueOf(record.getId()), trimmedOrNull(reason));
        } catch (Exception e) {
            // deliberately swallowed
        }
    }
}

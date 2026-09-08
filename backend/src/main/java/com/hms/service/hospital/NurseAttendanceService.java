package com.hms.service.hospital;

import com.hms.dto.AttendanceSheetRow;
import com.hms.dto.AttendanceSummary;
import com.hms.dto.MarkAttendanceRequest;
import com.hms.entity.AttendanceStatus;
import com.hms.entity.NurseAttendance;
import com.hms.entity.NurseProfile;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.NurseAttendanceRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.NurseShiftScheduleRepository;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NurseAttendanceService - daily nurse attendance (Nursing Mgmt Phase D).
 * One row per (nurse, date), upserted. Shift fields are snapshots of the
 * nurse's schedule for that date. Ward-scoped via NurseInchargeGuard; every
 * mark/correction is audited.
 */
@Service
public class NurseAttendanceService {

    @Autowired private NurseAttendanceRepository attendanceRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private NurseShiftScheduleRepository scheduleRepository;
    @Autowired private NurseInchargeGuard nurseInchargeGuard;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;

    @Transactional
    public NurseAttendance mark(MarkAttendanceRequest req) {
        Long hospitalId = requireHospitalId();
        if (req.getNurseProfileId() == null || req.getDate() == null) {
            throw new IllegalArgumentException("nurseProfileId and date are required");
        }
        NurseProfile p = nurseProfileRepository.findById(req.getNurseProfileId())
                .orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
        if (!hospitalId.equals(p.getHospitalId())) throw new UnauthorizedException("Nurse belongs to another hospital");
        if (!AttendanceStatus.isValid(req.getStatus())) throw new IllegalArgumentException("Invalid attendance status");
        nurseInchargeGuard.assertWardAccess(p.getWardId());

        NurseAttendance a = attendanceRepository
                .findByNurseProfileIdAndAttendanceDate(p.getId(), req.getDate())
                .orElseGet(NurseAttendance::new);
        String previous = a.getStatus();

        a.setHospitalId(hospitalId);
        a.setNurseProfileId(p.getId());
        a.setWardId(p.getWardId());
        a.setAttendanceDate(req.getDate());
        a.setStatus(req.getStatus());
        a.setCheckInTime(req.getCheckInTime());
        a.setCheckOutTime(req.getCheckOutTime());
        a.setRemarks(req.getRemarks());
        a.setMarkedByUserId(securityHelper.getCurrentUserId());
        scheduleRepository.findByNurseProfileIdAndShiftDate(p.getId(), req.getDate()).ifPresent(s -> {
            a.setShiftTemplateId(s.getShiftTemplateId());
            a.setShiftStartTime(s.getStartTime());
            a.setShiftEndTime(s.getEndTime());
        });
        NurseAttendance saved = attendanceRepository.save(a);

        String action = (previous == null) ? "ATTENDANCE_MARKED" : "ATTENDANCE_MODIFIED";
        String details = p.getName() + " " + req.getDate() + " : "
                + (previous == null ? req.getStatus() : previous + " -> " + req.getStatus());
        audit(action, details, hospitalId, saved.getId(), req.getRemarks());
        return saved;
    }

    public List<AttendanceSheetRow> getSheet(Long wardId, LocalDate date) {
        nurseInchargeGuard.assertWardAccess(wardId);
        Map<Long, AttendanceSheetRow> rows = new LinkedHashMap<>();

        scheduleRepository.findByWardIdAndShiftDateBetweenOrderByShiftDateAsc(wardId, date, date).forEach(s -> {
            AttendanceSheetRow r = new AttendanceSheetRow();
            r.setNurseProfileId(s.getNurseProfileId());
            r.setShiftTemplateId(s.getShiftTemplateId());
            r.setShiftStartTime(s.getStartTime());
            r.setShiftEndTime(s.getEndTime());
            rows.put(s.getNurseProfileId(), r);
        });

        for (NurseAttendance a : attendanceRepository.findByWardIdAndAttendanceDate(wardId, date)) {
            AttendanceSheetRow r = rows.computeIfAbsent(a.getNurseProfileId(), k -> {
                AttendanceSheetRow n = new AttendanceSheetRow();
                n.setNurseProfileId(k);
                return n;
            });
            r.setAttendancePublicId(a.getPublicId());
            r.setStatus(a.getStatus());
            r.setCheckInTime(a.getCheckInTime());
            r.setCheckOutTime(a.getCheckOutTime());
            r.setRemarks(a.getRemarks());
            if (r.getShiftStartTime() == null) {
                r.setShiftTemplateId(a.getShiftTemplateId());
                r.setShiftStartTime(a.getShiftStartTime());
                r.setShiftEndTime(a.getShiftEndTime());
            }
        }

        rows.values().forEach(r -> nurseProfileRepository.findById(r.getNurseProfileId())
                .ifPresent(p -> r.setNurseName(p.getName())));
        return new ArrayList<>(rows.values());
    }

    public AttendanceSummary summary(Long wardId, LocalDate date) {
        List<AttendanceSheetRow> sheet = getSheet(wardId, date); // ward-scoped inside
        AttendanceSummary s = new AttendanceSummary();
        for (AttendanceSheetRow r : sheet) {
            String st = r.getStatus();
            if (st == null) { s.setUnmarked(s.getUnmarked() + 1); continue; }
            switch (st) {
                case "PRESENT" -> s.setPresent(s.getPresent() + 1);
                case "ABSENT" -> s.setAbsent(s.getAbsent() + 1);
                case "HALF_DAY" -> s.setHalfDay(s.getHalfDay() + 1);
                case "LEAVE" -> s.setLeave(s.getLeave() + 1);
                case "HOLIDAY" -> s.setHoliday(s.getHoliday() + 1);
                case "LATE" -> s.setLate(s.getLate() + 1);
                default -> s.setUnmarked(s.getUnmarked() + 1);
            }
        }
        return s;
    }

    public List<NurseAttendance> getMyAttendance(LocalDate from, LocalDate to) {
        requireHospitalId();
        Long profileId = nurseProfileRepository.findByUserId(securityHelper.getCurrentUserId())
                .map(NurseProfile::getId).orElse(null);
        if (profileId == null) return List.of();
        return attendanceRepository.findByNurseProfileIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(profileId, from, to);
    }

    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found");
        return h;
    }

    /** Audits the attendance write and pushes it: the incharge's sheet must move as nurses mark in. */
    private void audit(String action, String details, Long hospitalId, Long id, String reason) {
        notifier.refresh(hospitalId);
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(), hospitalId,
                    "NURSE_ATTENDANCE", String.valueOf(id), reason);
        } catch (Exception e) { /* best-effort */ }
    }
}

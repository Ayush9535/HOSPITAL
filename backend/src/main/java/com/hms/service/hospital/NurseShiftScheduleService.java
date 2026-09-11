package com.hms.service.hospital;

import com.hms.dto.AssignShiftRequest;
import com.hms.dto.NurseShiftScheduleView;
import com.hms.dto.RangeFillShiftRequest;
import com.hms.entity.NurseProfile;
import com.hms.entity.NurseShiftSchedule;
import com.hms.entity.ShiftTemplate;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.NurseShiftScheduleRepository;
import com.hms.repository.ShiftTemplateRepository;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * NurseShiftScheduleService - assigns nurses to shifts (Nursing Mgmt Phase B).
 * A schedule row SNAPSHOTS its template's times, so editing a template only
 * changes FUTURE schedules (applyTemplateChangeToFuture); history is preserved.
 * Ward-scoped: an incharge may only schedule nurses in their own wards.
 */
@Service
public class NurseShiftScheduleService {
    @Autowired private NurseShiftScheduleRepository scheduleRepository;
    @Autowired private ShiftTemplateRepository shiftTemplateRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private NurseInchargeGuard nurseInchargeGuard;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    @Transactional
    public NurseShiftSchedule assign(AssignShiftRequest req) {
        Long hospitalId = requireHospitalId();
        NurseProfile p = requireNurse(req.getNurseProfileId(), hospitalId);
        nurseInchargeGuard.assertWardAccess(p.getWardId());
        ShiftTemplate t = requireTemplate(req.getShiftTemplatePublicId(), hospitalId);
        if (req.getDate() == null) throw new IllegalArgumentException("Date is required");
        NurseShiftSchedule s = scheduleRepository.findByNurseProfileIdAndShiftDate(p.getId(), req.getDate())
                .orElseGet(NurseShiftSchedule::new);
        applyTo(s, hospitalId, p, req.getDate(), t);
        NurseShiftSchedule saved = scheduleRepository.save(s);
        audit("NURSE_SHIFT_ASSIGNED", p.getName() + " " + req.getDate() + " " + t.getName(), hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    @Transactional
    public int rangeFill(RangeFillShiftRequest req) {
        Long hospitalId = requireHospitalId();
        NurseProfile p = requireNurse(req.getNurseProfileId(), hospitalId);
        nurseInchargeGuard.assertWardAccess(p.getWardId());
        ShiftTemplate t = requireTemplate(req.getShiftTemplatePublicId(), hospitalId);
        if (req.getFromDate() == null || req.getToDate() == null || req.getToDate().isBefore(req.getFromDate())) {
            throw new IllegalArgumentException("Valid from/to dates are required");
        }
        int count = 0;
        for (LocalDate d = req.getFromDate(); !d.isAfter(req.getToDate()); d = d.plusDays(1)) {
            if (req.getDaysOfWeek() != null && !req.getDaysOfWeek().isEmpty()
                    && !req.getDaysOfWeek().contains(d.getDayOfWeek().getValue())) continue;
            NurseShiftSchedule s = scheduleRepository.findByNurseProfileIdAndShiftDate(p.getId(), d)
                    .orElseGet(NurseShiftSchedule::new);
            applyTo(s, hospitalId, p, d, t);
            scheduleRepository.save(s);
            count++;
        }
        audit("NURSE_SHIFT_RANGE_FILLED", p.getName() + " " + req.getFromDate() + ".." + req.getToDate() + " x" + count, hospitalId, p.getId());
        broadcastRefresh(hospitalId);
        return count;
    }

    @Transactional
    public void remove(String publicId) {
        Long hospitalId = requireHospitalId();
        NurseShiftSchedule s = scheduleRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found"));
        if (!hospitalId.equals(s.getHospitalId())) throw new UnauthorizedException("Belongs to another hospital");
        nurseInchargeGuard.assertWardAccess(s.getWardId());
        scheduleRepository.delete(s);
        audit("NURSE_SHIFT_REMOVED", publicId, hospitalId, s.getId());
        broadcastRefresh(hospitalId);
    }

    /** Future-dated schedules using this template adopt the new times; past rows unchanged. */
    @Transactional
    public int applyTemplateChangeToFuture(Long templateId, LocalTime start, LocalTime end) {
        return scheduleRepository.applyTemplateChangeToFuture(templateId, start, end, LocalDate.now(java.time.ZoneId.systemDefault()));
    }

    public List<NurseShiftScheduleView> getWardSchedule(Long wardId, LocalDate from, LocalDate to) {
        nurseInchargeGuard.assertWardAccess(wardId);
        return decorate(scheduleRepository.findByWardIdAndShiftDateBetweenOrderByShiftDateAsc(wardId, from, to));
    }

    public List<NurseShiftScheduleView> getMySchedule(LocalDate from, LocalDate to) {
        requireHospitalId();
        Long profileId = nurseProfileRepository.findByUserId(securityHelper.getCurrentUserId())
                .map(NurseProfile::getId).orElse(null);
        if (profileId == null) return List.of();
        return decorate(scheduleRepository.findByNurseProfileIdAndShiftDateBetweenOrderByShiftDateAsc(profileId, from, to));
    }

    /** True iff the nurse has a shift today whose window contains 'now'. */
    /**
     * A nurse's shift for today, as the roster actually holds it, or null when none is rostered.
     *
     * <p>One resolver, deliberately. The incharge's roster derived a live answer from
     * nurse_shift_schedules while the admin's nurse list showed no shift at all, so the two
     * screens disagreed about the same nurse -- and NurseProfile.onShift, a flag nothing keeps
     * up to date, sat there looking like a third answer. This is the only place either screen may
     * ask, and both now report the same thing.
     *
     * <p>Today's row is the effective assignment: a substitution or a one-off change is written
     * as that day's schedule, so reading today reads the substitution without needing to know one
     * happened. Templates are snapshotted onto the row at assignment time, which is why the times
     * come from the schedule and not from the template as it stands now.
     */
    public EffectiveShift effectiveShiftToday(Long nurseProfileId) {
        return scheduleRepository.findByNurseProfileIdAndShiftDate(nurseProfileId, LocalDate.now())
                .map(s -> {
                    // Scoped to the schedule's own facility rather than loaded by raw id: the
                    // template id is read off a row, and a by-id load is exactly what the tenant
                    // fence asks us not to do even where the id looks trustworthy.
                    String name = shiftTemplateRepository
                            .findByIdAndHospitalId(s.getShiftTemplateId(), s.getHospitalId())
                            .map(ShiftTemplate::getName).orElse(null);
                    return new EffectiveShift(name, s.getStartTime(), s.getEndTime(),
                            withinWindow(s.getStartTime(), s.getEndTime(), LocalTime.now()));
                })
                .orElse(null);
    }

    /** Today's rostered shift: its name, its window, and whether the nurse is inside it now. */
    public record EffectiveShift(String shiftName, LocalTime startTime, LocalTime endTime,
            boolean onShiftNow) {}

    public boolean isOnShiftNow(Long nurseProfileId) {
        return scheduleRepository.findByNurseProfileIdAndShiftDate(nurseProfileId, LocalDate.now())
                .map(s -> withinWindow(s.getStartTime(), s.getEndTime(), LocalTime.now()))
                .orElse(false);
    }

    /** end<=start means the shift crosses midnight (e.g. 22:00-06:00). */
    static boolean withinWindow(LocalTime start, LocalTime end, LocalTime now) {
        if (end.isAfter(start)) return !now.isBefore(start) && now.isBefore(end);
        return !now.isBefore(start) || now.isBefore(end);
    }

    private void applyTo(NurseShiftSchedule s, Long hospitalId, NurseProfile p, LocalDate date, ShiftTemplate t) {
        s.setHospitalId(hospitalId);
        s.setNurseProfileId(p.getId());
        s.setWardId(p.getWardId());
        s.setShiftDate(date);
        s.setShiftTemplateId(t.getId());
        s.setStartTime(t.getStartTime());
        s.setEndTime(t.getEndTime());
        s.setCreatedByUserId(securityHelper.getCurrentUserId());
    }

    private List<NurseShiftScheduleView> decorate(List<NurseShiftSchedule> list) {
        List<NurseShiftScheduleView> out = new ArrayList<>();
        for (NurseShiftSchedule s : list) {
            NurseShiftScheduleView v = NurseShiftScheduleView.of(s);
            nurseProfileRepository.findById(s.getNurseProfileId()).ifPresent(p -> v.setNurseName(p.getName()));
            out.add(v);
        }
        return out;
    }

    private NurseProfile requireNurse(Long id, Long hospitalId) {
        if (id == null) throw new IllegalArgumentException("nurseProfileId is required");
        NurseProfile p = nurseProfileRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
        if (!hospitalId.equals(p.getHospitalId())) throw new UnauthorizedException("Nurse belongs to another hospital");
        return p;
    }
    private ShiftTemplate requireTemplate(String publicId, Long hospitalId) {
        ShiftTemplate t = shiftTemplateRepository.findByPublicId(publicId).orElseThrow(() -> new IllegalArgumentException("Shift template not found"));
        if (!hospitalId.equals(t.getHospitalId())) throw new UnauthorizedException("Template belongs to another hospital");
        return t;
    }
    private Long requireHospitalId() { Long h = securityHelper.getCurrentHospitalId(); if (h == null) throw new UnauthorizedException("Hospital ID not found"); return h; }
    private void audit(String a, String d, Long h, Long id) { try { auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "NURSE_SHIFT", String.valueOf(id), null); } catch (Exception e) {} }
    private void broadcastRefresh(Long hospitalId) { try { webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}"); } catch (Exception e) {} }
}

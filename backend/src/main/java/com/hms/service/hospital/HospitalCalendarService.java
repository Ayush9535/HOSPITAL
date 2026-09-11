package com.hms.service.hospital;

import com.hms.dto.CalendarEventRequest;
import com.hms.entity.CalendarEvent;
import com.hms.entity.NurseAttendance;
import com.hms.entity.NurseShiftSchedule;
import com.hms.entity.Surgery;
import com.hms.entity.Ward;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.*;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * HospitalCalendarService - aggregates nurse shifts, attendance, OT surgeries,
 * and calendar_events into a month grid + day detail (Nursing Mgmt Phase G).
 * Ward-scoped for an incharge (myWardIds); admin sees all. Surgeries are
 * hospital-wide for admin, and for an incharge limited to surgeries whose IPD
 * admission is in one of their wards.
 */
@Service
public class HospitalCalendarService {
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseInchargeGuard nurseInchargeGuard;
    @Autowired private WardRepository wardRepository;
    @Autowired private NurseShiftScheduleRepository shiftScheduleRepository;
    @Autowired private NurseAttendanceRepository attendanceRepository;
    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private com.hms.repository.OtRoomRepository otRoomRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private CalendarEventRepository calendarEventRepository;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    private static final List<String> CAL_SURGERY_STATUSES =
            List.of(Surgery.SCHEDULED, Surgery.IN_PROGRESS, Surgery.COMPLETED);
    private static final Set<String> EVENT_TYPES =
            Set.of(CalendarEvent.HOLIDAY, CalendarEvent.EVENT, CalendarEvent.NOTICE);

    public List<Map<String, Object>> monthSummary(int year, int month) {
        Long hospitalId = requireHospitalId();
        Set<Long> wardIds = new HashSet<>(nurseInchargeGuard.myWardIds());
        YearMonth ym = YearMonth.of(year, month);
        LocalDate first = ym.atDay(1), last = ym.atEndOfMonth();

        Map<LocalDate, Integer> shiftCounts = new HashMap<>();
        if (!wardIds.isEmpty()) {
            for (NurseShiftSchedule s : shiftScheduleRepository.findByWardIdInAndShiftDateBetween(wardIds, first, last)) {
                shiftCounts.merge(s.getShiftDate(), 1, Integer::sum);
            }
        }
        Map<LocalDate, Integer> surgeryCounts = new HashMap<>();
        for (Surgery sg : scopedSurgeries(hospitalId, wardIds, first, last)) {
            surgeryCounts.merge(sg.getScheduledAt().toLocalDate(), 1, Integer::sum);
        }
        List<CalendarEvent> events = calendarEventRepository
                .findByHospitalIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(hospitalId, last, first);

        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            List<Map<String, Object>> dayEvents = new ArrayList<>();
            boolean hasHoliday = false;
            for (CalendarEvent e : events) {
                if (!d.isBefore(e.getFromDate()) && !d.isAfter(e.getToDate())) {
                    dayEvents.add(Map.of("publicId", e.getPublicId(), "title", e.getTitle(), "type", e.getEventType()));
                    if (CalendarEvent.HOLIDAY.equals(e.getEventType())) hasHoliday = true;
                }
            }
            Map<String, Object> day = new HashMap<>();
            day.put("date", d.toString());
            day.put("shiftCount", shiftCounts.getOrDefault(d, 0));
            day.put("surgeryCount", surgeryCounts.getOrDefault(d, 0));
            day.put("events", dayEvents);
            day.put("hasHoliday", hasHoliday);
            days.add(day);
        }
        return days;
    }

    public Map<String, Object> dayDetail(LocalDate date) {
        Long hospitalId = requireHospitalId();
        Set<Long> wardIds = new HashSet<>(nurseInchargeGuard.myWardIds());
        Map<Long, String> wardNames = wardNameMap(hospitalId);

        List<Map<String, Object>> shifts = new ArrayList<>();
        if (!wardIds.isEmpty()) {
            for (NurseShiftSchedule s : shiftScheduleRepository.findByWardIdInAndShiftDateBetween(wardIds, date, date)) {
                Map<String, Object> m = new HashMap<>();
                m.put("nurseName", nurseProfileRepository.findById(s.getNurseProfileId())
                        .map(com.hms.entity.NurseProfile::getName).orElse("Nurse #" + s.getNurseProfileId()));
                m.put("wardName", wardNames.getOrDefault(s.getWardId(), ""));
                m.put("startTime", String.valueOf(s.getStartTime()));
                m.put("endTime", String.valueOf(s.getEndTime()));
                shifts.add(m);
            }
        }
        int present = 0, absent = 0, onLeave = 0;
        if (!wardIds.isEmpty()) {
            for (NurseAttendance a : attendanceRepository.findByWardIdInAndAttendanceDateBetween(wardIds, date, date)) {
                switch (a.getStatus() == null ? "" : a.getStatus()) {
                    case "PRESENT", "LATE", "HALF_DAY" -> present++;
                    case "ABSENT" -> absent++;
                    case "LEAVE" -> onLeave++;
                    default -> { }
                }
            }
        }
        List<Map<String, Object>> surgeries = new ArrayList<>();
        for (Surgery sg : scopedSurgeries(hospitalId, wardIds, date, date)) {
            Map<String, Object> m = new HashMap<>();
            m.put("patientName", patientRepository.findById(sg.getPatientId())
                    .map(com.hms.entity.Patient::getName).orElse("Patient #" + sg.getPatientId()));
            m.put("scheduledTime", String.valueOf(sg.getScheduledAt().toLocalTime()));
            m.put("surgeonName", sg.getSurgeonName() != null ? sg.getSurgeonName() : "");
            m.put("procedureName", sg.getProcedureName() != null ? sg.getProcedureName() : "");
            // Room axis: prefer the theatre name, falling back to the legacy OT ward.
            String theatre = sg.getOtRoomId() != null
                    ? otRoomRepository.findById(sg.getOtRoomId()).map(com.hms.entity.OtRoom::getName).orElse("")
                    : (sg.getOtWardId() != null ? wardNames.getOrDefault(sg.getOtWardId(), "") : "");
            m.put("otRoomName", theatre);
            m.put("otWardName", sg.getOtWardId() != null ? wardNames.getOrDefault(sg.getOtWardId(), "") : "");
            m.put("status", sg.getStatus());
            surgeries.add(m);
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (CalendarEvent e : calendarEventRepository
                .findByHospitalIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(hospitalId, date, date)) {
            Map<String, Object> m = new HashMap<>();
            m.put("publicId", e.getPublicId());
            m.put("title", e.getTitle());
            m.put("type", e.getEventType());
            m.put("description", e.getDescription());
            m.put("fromDate", e.getFromDate().toString());
            m.put("toDate", e.getToDate().toString());
            events.add(m);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("date", date.toString());
        out.put("shifts", shifts);
        out.put("attendance", Map.of("present", present, "absent", absent, "onLeave", onLeave));
        out.put("surgeries", surgeries);
        out.put("events", events);
        return out;
    }

    public List<CalendarEvent> listEvents() {
        return calendarEventRepository
                .findByHospitalIdAndToDateGreaterThanEqualOrderByFromDateAsc(requireHospitalId(), LocalDate.now());
    }

    @Transactional
    public CalendarEvent createEvent(CalendarEventRequest req) {
        Long hospitalId = requireHospitalId();
        validate(req);
        CalendarEvent e = new CalendarEvent();
        e.setHospitalId(hospitalId);
        apply(e, req);
        e.setCreatedByUserId(securityHelper.getCurrentUserId());
        CalendarEvent saved = calendarEventRepository.save(e);
        audit("CALENDAR_EVENT_CREATED", saved.getTitle(), hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    @Transactional
    public CalendarEvent updateEvent(String publicId, CalendarEventRequest req) {
        Long hospitalId = requireHospitalId();
        validate(req);
        CalendarEvent e = requireEvent(publicId, hospitalId);
        apply(e, req);
        CalendarEvent saved = calendarEventRepository.save(e);
        audit("CALENDAR_EVENT_UPDATED", saved.getTitle(), hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    @Transactional
    public void deleteEvent(String publicId) {
        Long hospitalId = requireHospitalId();
        CalendarEvent e = requireEvent(publicId, hospitalId);
        calendarEventRepository.delete(e);
        audit("CALENDAR_EVENT_DELETED", publicId, hospitalId, e.getId());
        broadcastRefresh(hospitalId);
    }

    private List<Surgery> scopedSurgeries(Long hospitalId, Set<Long> wardIds, LocalDate from, LocalDate to) {
        boolean isAdmin = "HOSPITAL_ADMIN".equals(securityHelper.getCurrentUserRole());
        List<Surgery> out = new ArrayList<>();
        for (Surgery sg : surgeryRepository.findByHospitalIdAndStatusInOrderByScheduledAtAsc(hospitalId, CAL_SURGERY_STATUSES)) {
            if (sg.getScheduledAt() == null) continue;
            LocalDate d = sg.getScheduledAt().toLocalDate();
            if (d.isBefore(from) || d.isAfter(to)) continue;
            if (!isAdmin) {
                // A day-care procedure has no admission, so it sits in no ward and cannot be
                // scoped to an incharge. findById(null) would throw, not return empty.
                if (sg.getIpdAdmissionId() == null) continue;
                Long admWard = ipdAdmissionRepository.findById(sg.getIpdAdmissionId())
                        .map(com.hms.entity.IpdAdmission::getWardId).orElse(null);
                if (admWard == null || !wardIds.contains(admWard)) continue;
            }
            out.add(sg);
        }
        return out;
    }

    private Map<Long, String> wardNameMap(Long hospitalId) {
        Map<Long, String> m = new HashMap<>();
        for (Ward w : wardRepository.findByHospitalId(hospitalId)) m.put(w.getWardId(), w.getWardName());
        return m;
    }

    private void validate(CalendarEventRequest req) {
        if (req.getTitle() == null || req.getTitle().trim().isEmpty()) throw new IllegalArgumentException("Title is required");
        if (req.getEventType() == null || !EVENT_TYPES.contains(req.getEventType()))
            throw new IllegalArgumentException("Invalid event type");
        if (req.getFromDate() == null || req.getToDate() == null || req.getToDate().isBefore(req.getFromDate()))
            throw new IllegalArgumentException("Valid from/to dates are required");
    }

    private void apply(CalendarEvent e, CalendarEventRequest req) {
        e.setTitle(req.getTitle().trim());
        e.setEventType(req.getEventType());
        e.setFromDate(req.getFromDate());
        e.setToDate(req.getToDate());
        e.setDescription(req.getDescription());
    }

    private CalendarEvent requireEvent(String publicId, Long hospitalId) {
        CalendarEvent e = calendarEventRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("Calendar event not found"));
        if (!hospitalId.equals(e.getHospitalId())) throw new UnauthorizedException("Belongs to another hospital");
        return e;
    }

    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found");
        return h;
    }

    private void audit(String a, String d, Long h, Long id) {
        try {
            auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "CALENDAR_EVENT", String.valueOf(id), null);
        } catch (Exception e) { /* best-effort */ }
    }

    private void broadcastRefresh(Long hospitalId) {
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) { /* best-effort */ }
    }
}

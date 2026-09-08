package com.hms.service.hospital.ot;

import com.hms.entity.Surgery;
import com.hms.entity.SurgeryStatus;
import com.hms.entity.WhoChecklist;
import com.hms.exception.UnauthorizedException;
import com.hms.entity.OtRoomOccupancy;
import com.hms.repository.OtRoomOccupancyRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.repository.SurgeryStateTransitionRepository;
import com.hms.repository.WhoChecklistRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OtAnalyticsService - the four numbers a hospital owner asks for on day one: today's
 * cases, completed, cancelled (by reason), and how busy the theatres are.
 *
 * Every figure is a query over surgery_state_transitions plus the day's schedule -- no
 * new writes. Ships with the policy engine (Phase 5) rather than at the very end, because
 * by now the transition table already holds every fact the numbers need.
 */
@Service
public class OtAnalyticsService {

    @Autowired private SurgeryStateTransitionRepository transitionRepository;
    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private WhoChecklistRepository whoRepository;
    @Autowired private OtRoomOccupancyRepository occupancyRepository;
    @Autowired private SecurityContextHelper securityHelper;

    /** A first case starting within this many minutes of its scheduled time counts as on-time. */
    private static final int ON_TIME_GRACE_MINUTES = 15;
    /** A completed case counts as an unplanned return if the patient was operated again within this window. */
    private static final int UNPLANNED_RETURN_DAYS = 30;

    public Map<String, Object> summary(LocalDate date) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        LocalDate day = date == null ? LocalDate.now() : date;
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.plusDays(1).atStartOfDay();

        List<Surgery> scheduled = surgeryRepository.findScheduledBetween(hospitalId, from, to);
        long completed = transitionRepository.countReaching(hospitalId, SurgeryStatus.COMPLETED.name(), from, to);
        long cancelled = transitionRepository.countReaching(hospitalId, SurgeryStatus.CANCELLED.name(), from, to);
        long inProgress = scheduled.stream()
                .filter(s -> SurgeryStatus.IN_PROGRESS.name().equals(s.getStatus())).count();

        List<Map<String, Object>> byReason = new ArrayList<>();
        for (Object[] r : transitionRepository.cancellationsByReason(hospitalId, from, to)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reason", r[0]);
            row.put("count", ((Number) r[1]).longValue());
            byReason.add(row);
        }

        // Booked theatre-minutes for the day, a simple utilisation proxy until the occupancy
        // timeline (Phase 9) exists. Honest about being an estimate, not a measured figure.
        long bookedMinutes = scheduled.stream()
                .filter(s -> !SurgeryStatus.CANCELLED.name().equals(s.getStatus()))
                .mapToLong(s -> s.getEstimatedDurationMinutes() == null ? 60 : s.getEstimatedDurationMinutes())
                .sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("scheduledToday", scheduled.size());
        out.put("inProgress", inProgress);
        out.put("completedToday", completed);
        out.put("cancelledToday", cancelled);
        out.put("cancellationsByReason", byReason);
        out.put("bookedTheatreMinutes", bookedMinutes);
        return out;
    }

    /**
     * NABH surgical-care indicators over a date range. Every figure is a query over what
     * the earlier phases already record -- no new writes, no new tables.
     */
    public Map<String, Object> nabhIndicators(LocalDate from, LocalDate to) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        LocalDate start = from == null ? LocalDate.now(java.time.ZoneId.systemDefault()).minusDays(30) : from;
        LocalDate end = to == null ? LocalDate.now(java.time.ZoneId.systemDefault()) : to;
        LocalDateTime f = start.atStartOfDay();
        LocalDateTime t = end.plusDays(1).atStartOfDay();

        List<Surgery> inRange = surgeryRepository.findScheduledBetween(hospitalId, f, t);
        long completed = inRange.stream()
                .filter(s -> SurgeryStatus.COMPLETED.name().equals(s.getStatus())
                        || SurgeryStatus.CLOSED.name().equals(s.getStatus())).count();

        // WHO compliance: of completed cases, how many have a fully signed checklist.
        long whoCompliant = 0;
        for (Surgery s : inRange) {
            if (!SurgeryStatus.COMPLETED.name().equals(s.getStatus())
                    && !SurgeryStatus.CLOSED.name().equals(s.getStatus())) continue;
            WhoChecklist c = whoRepository.findBySurgeryId(s.getId()).orElse(null);
            if (c != null && c.getSignInAt() != null && c.getTimeOutAt() != null && c.getSignOutAt() != null) {
                whoCompliant++;
            }
        }

        long cancelled = transitionRepository.countReaching(hospitalId, SurgeryStatus.CANCELLED.name(), f, t);
        long booked = inRange.size() + cancelled;
        long electiveCancelled = 0;
        List<Map<String, Object>> byReason = new ArrayList<>();
        for (Object[] r : transitionRepository.cancellationsByReason(hospitalId, f, t)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reason", r[0]);
            row.put("count", ((Number) r[1]).longValue());
            byReason.add(row);
            electiveCancelled += ((Number) r[1]).longValue();
        }

        // Turnover and utilisation from the occupancy timeline. Turnover is the gap between
        // one span's close and the next span's open in the SAME room; utilisation is total
        // occupied minutes over the spans observed.
        List<OtRoomOccupancy> spans = occupancyRepository.findClosedSpans(hospitalId, f, t);
        long occupiedMinutes = 0;
        long turnoverSum = 0;
        long turnoverCount = 0;
        Long prevRoom = null;
        LocalDateTime prevClose = null;
        for (OtRoomOccupancy span : spans) { // room-ordered, then by start
            occupiedMinutes += java.time.Duration.between(
                    span.getOccupiedFrom().atZone(java.time.ZoneId.systemDefault()),
                    span.getOccupiedTo().atZone(java.time.ZoneId.systemDefault())).toMinutes();
            if (span.getOtRoomId().equals(prevRoom) && prevClose != null
                    && !span.getOccupiedFrom().isBefore(prevClose)) {
                turnoverSum += java.time.Duration.between(
                        prevClose.atZone(java.time.ZoneId.systemDefault()),
                        span.getOccupiedFrom().atZone(java.time.ZoneId.systemDefault())).toMinutes();
                turnoverCount++;
            }
            prevRoom = span.getOtRoomId();
            prevClose = span.getOccupiedTo();
        }

        // First-case on-time start: the earliest scheduled case per room-day, and whether its
        // theatre span opened within the grace window of its scheduled time.
        int firstCases = 0, firstOnTime = 0;
        Map<String, Surgery> firstByRoomDay = new LinkedHashMap<>();
        for (Surgery s : inRange) {
            if (s.getScheduledAt() == null || s.getOtRoomId() == null) continue;
            String key = s.getOtRoomId() + "|" + s.getScheduledAt().toLocalDate();
            Surgery cur = firstByRoomDay.get(key);
            if (cur == null || s.getScheduledAt().isBefore(cur.getScheduledAt())) firstByRoomDay.put(key, s);
        }
        for (Surgery first : firstByRoomDay.values()) {
            OtRoomOccupancy span = occupancyRepository.findBySurgeryIdAndOccupiedToIsNull(first.getId())
                    .orElse(null);
            LocalDateTime actualStart = span != null ? span.getOccupiedFrom() : first.getStartedAt();
            if (actualStart == null) continue; // never started; not counted either way
            firstCases++;
            if (!actualStart.isAfter(first.getScheduledAt().plusMinutes(ON_TIME_GRACE_MINUTES))) firstOnTime++;
        }

        long unplannedReturns = surgeryRepository.countUnplannedReturns(hospitalId, f, t, UNPLANNED_RETURN_DAYS);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", start.toString());
        out.put("to", end.toString());
        out.put("completed", completed);
        out.put("cancelled", cancelled);
        out.put("whoCompliancePercent", completed == 0 ? null : Math.round(whoCompliant * 1000.0 / completed) / 10.0);
        out.put("cancellationRatePercent", booked == 0 ? null : Math.round(electiveCancelled * 1000.0 / booked) / 10.0);
        out.put("cancellationsByReason", byReason);
        out.put("occupiedTheatreMinutes", occupiedMinutes);
        out.put("averageTurnoverMinutes", turnoverCount == 0 ? null : Math.round(turnoverSum * 10.0 / turnoverCount) / 10.0);
        out.put("firstCaseOnTimePercent", firstCases == 0 ? null : Math.round(firstOnTime * 1000.0 / firstCases) / 10.0);
        out.put("unplannedReturns", unplannedReturns);
        return out;
    }
}

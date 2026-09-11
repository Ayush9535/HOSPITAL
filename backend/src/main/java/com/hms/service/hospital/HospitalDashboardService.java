package com.hms.service.hospital;

import com.hms.dto.DashboardOverviewDTO;
import com.hms.entitlement.EntitlementRegistry;
import com.hms.entity.BedStatus;
import com.hms.entity.Hospital;
import com.hms.exception.ResourceNotFoundException;
import com.hms.repository.*;
import com.hms.repository.pharmacy.PharmacySaleRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Hospital Admin Overview, assembled from aggregates.
 *
 * <p>Two rules shape this class.
 *
 * <p><b>A capability the tenant does not hold produces no block and runs no query.</b> Not a zero,
 * not an empty object, not a 403 — the key is simply absent. Hiding a card in the browser while the
 * server still runs the query would be a different thing entirely: it would leak the shape of what
 * the tenant does not own into the response and waste the work. So the entitlement check happens
 * before the aggregate, and it is the only thing standing between the two.
 *
 * <p><b>Everything is counted in SQL.</b> The existing analytics endpoint answers similar questions
 * by loading up to a hundred thousand OPD rows, every appointment the hospital has ever had, and
 * every bed, then folding them in Java. That is the thing this service exists not to be. Nothing
 * here returns an entity list; the heaviest result is one row per calendar day.
 */
@Service
public class HospitalDashboardService {

    /** Bucket for visits whose speciality cannot be attributed within this tenant. */
    static final String UNASSIGNED = "Unassigned";

    /** The dashboard shows the busiest few; the rest belong in a report, not on an overview. */
    private static final int TOP_SPECIALITIES = 5;

    private static final String INR = "INR";

    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private BusinessClock businessClock;
    @Autowired private HospitalRepository hospitalRepository;

    @Autowired private PatientRepository patientRepository;
    @Autowired private MedicalRecordRepository medicalRecordRepository;
    @Autowired private OpdRepository opdRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private BedRepository bedRepository;
    @Autowired private BillingPaymentRepository billingPaymentRepository;
    @Autowired private PharmacySaleRepository pharmacySaleRepository;

    @Transactional(readOnly = true)
    public DashboardOverviewDTO getOverview(DashboardRange range) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new com.hms.exception.UnauthorizedException("Hospital context not found");
        }

        // The live hospital row, never the token's module claim: a plan change has to take effect
        // on the next request, not on the user's next login. resolve() then expands what the plan
        // implies — BEDS is granted by IPD and is never persisted on its own, so reading the raw
        // list would hide the bed card from every hospital that has one.
        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));
        Set<String> capabilities = EntitlementRegistry.resolve(hospital.getModules());

        LocalDate today = businessClock.today();
        LocalDateTime from = range.from(today);
        LocalDateTime toExclusive = range.toExclusive(today);

        DashboardOverviewDTO dto = new DashboardOverviewDTO();
        dto.setRange(range.name());
        dto.setFrom(from);
        dto.setToExclusive(toExclusive);
        dto.setTimezone(businessClock.zoneId().getId());
        dto.setCore(core(hospitalId));

        if (capabilities.contains(EntitlementRegistry.OPD)) {
            dto.setOpd(opdBlock(hospitalId, range, today, from, toExclusive));
        }
        if (capabilities.contains(EntitlementRegistry.IPD)) {
            dto.setIpd(ipdBlock(hospitalId, range, today, from, toExclusive));
        }
        if (capabilities.contains(EntitlementRegistry.BEDS)) {
            dto.setBeds(bedsBlock(hospitalId));
        }
        if (capabilities.contains(EntitlementRegistry.BILLING)) {
            dto.setBilling(billingBlock(hospitalId, from, toExclusive));
        }
        if (capabilities.contains(EntitlementRegistry.PHARMACY)) {
            dto.setPharmacy(pharmacyBlock(hospitalId, from, toExclusive));
        }
        return dto;
    }

    private DashboardOverviewDTO.Core core(Long hospitalId) {
        // Registered patients is a running total, not a windowed one: the range selector moves the
        // activity numbers, never the size of the roll. Active only, which is what
        // HospitalStatsService and PatientService already mean by a hospital's patient count.
        return new DashboardOverviewDTO.Core(patientRepository.countByHospitalIdAndIsActiveTrue(hospitalId));
    }

    private DashboardOverviewDTO.OpdBlock opdBlock(Long hospitalId, DashboardRange range,
            LocalDate today, LocalDateTime from, LocalDateTime toExclusive) {

        // Inside the OPD gate, so this query does not run for a hospital without the capability.
        long consultations = medicalRecordRepository.countOpdConsultationsInRange(hospitalId, from, toExclusive);

        List<DashboardOverviewDTO.VisitTypeCount> visitTypes = new ArrayList<>();
        long total = 0;
        for (Object[] row : opdRepository.countByVisitTypeInRange(hospitalId, from, toExclusive)) {
            // visit_type is nullable; keep the null group so the breakdown still sums to the total.
            String type = row[0] == null ? "UNSPECIFIED" : String.valueOf(row[0]);
            long count = asLong(row[1]);
            visitTypes.add(new DashboardOverviewDTO.VisitTypeCount(type, count));
            total += count;
        }

        List<DashboardOverviewDTO.Bucket> trend = zeroFilled(
                opdRepository.countPerDayInRange(hospitalId, from, toExclusive), range, today);

        List<DashboardOverviewDTO.SpecialityCount> specialities = new ArrayList<>();
        for (Object[] row : opdRepository.countBySpecialityInRange(
                hospitalId, from, toExclusive, PageRequest.of(0, TOP_SPECIALITIES))) {
            // NULL is the canonical unassigned bucket: unattributable visits and a doctor whose
            // speciality is literally "Unassigned" are already grouped together in SQL, so naming
            // it here cannot merge two separately ranked groups after the fact.
            String speciality = row[0] == null ? UNASSIGNED : String.valueOf(row[0]);
            specialities.add(new DashboardOverviewDTO.SpecialityCount(speciality, asLong(row[1])));
        }

        return new DashboardOverviewDTO.OpdBlock(consultations, total, trend, visitTypes, specialities);
    }

    private DashboardOverviewDTO.IpdBlock ipdBlock(Long hospitalId, DashboardRange range,
            LocalDate today, LocalDateTime from, LocalDateTime toExclusive) {

        List<DashboardOverviewDTO.Bucket> trend = zeroFilled(
                ipdAdmissionRepository.countAdmissionsPerDayInRange(hospitalId, from, toExclusive),
                range, today);
        // Admissions in the window, which is not the same as patients currently admitted. Only one
        // of those belongs next to a date range, and mixing them would make the number unreadable.
        long admissions = trend.stream().mapToLong(DashboardOverviewDTO.Bucket::count).sum();
        return new DashboardOverviewDTO.IpdBlock(admissions, trend);
    }

    private DashboardOverviewDTO.BedsBlock bedsBlock(Long hospitalId) {
        long occupied = 0, available = 0, cleaning = 0, maintenance = 0, unknown = 0;

        for (Object[] row : bedRepository.countByStatusForHospital(hospitalId)) {
            String status = row[0] == null ? null : String.valueOf(row[0]);
            long count = asLong(row[1]);
            if (BedStatus.OCCUPIED.equalsIgnoreCase(status)) occupied += count;
            else if (BedStatus.AVAILABLE.equalsIgnoreCase(status)) available += count;
            else if (BedStatus.CLEANING.equalsIgnoreCase(status)) cleaning += count;
            else if (BedStatus.MAINTENANCE.equalsIgnoreCase(status)) maintenance += count;
            // A status the domain does not define is reported rather than folded into a bucket it
            // might not belong in. It is still one of the hospital's beds, so it stays in usable
            // capacity below; it simply cannot be claimed as occupied, available or cleaning.
            else unknown += count;
        }

        // Every bed the hospital has, less the ones under maintenance. An unrecognised status is
        // still a bed and still counts toward the hospital's size; it simply cannot be claimed as
        // occupied, available or cleaning, so it is surfaced on its own instead of being guessed at.
        long allBeds = occupied + available + cleaning + maintenance + unknown;
        long usableCapacity = allBeds - maintenance;
        Double occupancyRate = usableCapacity == 0
                ? null
                : BigDecimal.valueOf(occupied * 100.0 / usableCapacity)
                        .setScale(1, RoundingMode.HALF_UP).doubleValue();

        return new DashboardOverviewDTO.BedsBlock(occupied, usableCapacity, available,
                cleaning, maintenance, unknown, occupancyRate, businessClock.now());
    }

    private DashboardOverviewDTO.BillingBlock billingBlock(Long hospitalId, LocalDateTime from, LocalDateTime toExclusive) {
        BigDecimal collected = billingPaymentRepository.sumCollectedInRange(hospitalId, from, toExclusive);
        return new DashboardOverviewDTO.BillingBlock(
                collected == null ? BigDecimal.ZERO : collected, INR);
    }

    private DashboardOverviewDTO.PharmacyBlock pharmacyBlock(Long hospitalId, LocalDateTime from, LocalDateTime toExclusive) {
        return new DashboardOverviewDTO.PharmacyBlock(
                pharmacySaleRepository.countPostedSalesInRange(hospitalId, from, toExclusive));
    }

    /**
     * Turn the days that had activity into every day of the range.
     *
     * <p>SQL returns only non-empty days, which would draw a chart with gaps where the quiet days
     * should be. Filling happens here rather than in SQL because a calendar is cheap to generate
     * and at most thirty entries; the alternative is a recursive date table for no benefit.
     */
    private List<DashboardOverviewDTO.Bucket> zeroFilled(List<Object[]> rows, DashboardRange range, LocalDate today) {
        Map<LocalDate, Long> byDay = new LinkedHashMap<>();
        for (Object[] row : rows) {
            LocalDate day = asLocalDate(row[0]);
            if (day != null) {
                byDay.merge(day, asLong(row[1]), Long::sum);
            }
        }
        List<DashboardOverviewDTO.Bucket> buckets = new ArrayList<>(range.bucketCount());
        LocalDate cursor = range.firstDay(today);
        for (int i = 0; i < range.bucketCount(); i++) {
            buckets.add(new DashboardOverviewDTO.Bucket(cursor, byDay.getOrDefault(cursor, 0L)));
            cursor = cursor.plusDays(1);
        }
        return buckets;
    }

    /** JPQL COUNT gives Long; a native COUNT(*) can arrive as BigInteger or Integer. */
    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** Native DATE() comes back as java.sql.Date on MySQL and as LocalDate on some H2 versions. */
    private static LocalDate asLocalDate(Object value) {
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime().toLocalDate();
        if (value != null) return LocalDate.parse(String.valueOf(value).substring(0, 10));
        return null;
    }
}

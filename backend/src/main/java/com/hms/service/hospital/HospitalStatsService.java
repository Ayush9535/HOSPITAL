package com.hms.service.hospital;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.hms.entity.Appointment;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.entity.Doctor;
import com.hms.repository.AppointmentRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.BillingRepository;
import com.hms.repository.pharmacy.PharmacySaleRepository;
import com.hms.repository.WardRepository;
import com.hms.repository.BedRepository;
import com.hms.entity.Billing;
import com.hms.entity.pharmacy.PharmacySale;
import com.hms.entity.Ward;
import com.hms.entity.Bed;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HospitalStatsService - Cached service to fetch and evict tenant-specific hospital stats.
 */
@Service
public class HospitalStatsService {
    private static final String ACTIVITY_TYPE = "activityType";


    @Autowired
    private BusinessClock businessClock;

    @Autowired
    private PatientService patientService;

    @Autowired
    private DoctorService doctorService;

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private OpdRepository opdRepository;

    @Autowired
    private IpdAdmissionRepository ipdAdmissionRepository;

    @Autowired
    private DoctorRepository doctorRepository;
    
    @Autowired
    private BillingRepository billingRepository;

    @Autowired
    private PharmacySaleRepository pharmacySaleRepository;

    @Autowired
    private WardRepository wardRepository;

    @Autowired
    private BedRepository bedRepository;

    @Cacheable(value = "hospitalStats", key = "#hospitalId")
    public Map<String, Long> getStats(Long hospitalId) {
        long totalPatients = patientService.getPatientCount();
        long totalDoctors = doctorService.getAllDoctors(PageRequest.of(0, 1))
                .getTotalElements();
        long todaysAppointments = appointmentService.getTodaysAppointmentsCount();

        // Patient-focused stats for Overview tab
        // DATETIME values are IST business wall time, not UTC instants. Both windows are
        // half-open [from, toExclusive) so the last microsecond of a day cannot fall through
        // the gap that an inclusive LocalTime.MAX bound leaves against DATETIME(6).
        LocalDate today = businessClock.today();
        LocalDateTime startOfToday = today.atStartOfDay();
        LocalDateTime startOfTomorrow = today.plusDays(1).atStartOfDay();
        LocalDateTime startOfMonth = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime startOfNextMonth = today.withDayOfMonth(1).plusMonths(1).atStartOfDay();

        long patientsToday = patientRepository.countActiveInDateRange(
                hospitalId, startOfToday, startOfTomorrow);
        long patientsThisMonth = patientRepository.countActiveInDateRange(
                hospitalId, startOfMonth, startOfNextMonth);

        Map<String, Long> stats = new HashMap<>();
        // Existing keys (kept for backward compatibility)
        stats.put("totalPatients", totalPatients);
        stats.put("totalDoctors", totalDoctors);
        stats.put("todaysAppointments", todaysAppointments);
        // New patient-focused keys for Overview stat cards
        stats.put("totalRegisteredPatients", totalPatients);
        stats.put("patientsThisMonth", patientsThisMonth);
        stats.put("patientsToday", patientsToday);
        return stats;
    }

    /**
     * Get patient activity for a specific date.
     * Aggregates OPD visits, Appointments, and IPD admissions.
     * Returns list sorted by activity time descending (latest first).
     */
    public List<Map<String, Object>> getPatientActivityByDate(Long hospitalId, LocalDate date) {
        // Half-open business wall-clock interval; do not shift DATETIME bounds to UTC.
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime toExclusive = date.plusDays(1).atStartOfDay();

        List<Map<String, Object>> activities = new ArrayList<>();

        // Collect all doctor IDs and patient IDs for batch lookup
        Set<Long> doctorIds = new HashSet<>();
        Set<Long> patientIds = new HashSet<>();

        // 1. OPD records for this date
        List<Opd> opds = opdRepository.findActivityInDateRange(
                hospitalId, startOfDay, toExclusive, PageRequest.of(0, 1000)).getContent();
        for (Opd opd : opds) {
            if (opd.getDoctor() != null) doctorIds.add(opd.getDoctor().getId());
            if (opd.getPatient() != null) patientIds.add(opd.getPatient().getId());
        }

        // 2. Appointments for this date
        List<Appointment> appointments = appointmentRepository
                .findByHospitalIdAndAppointmentDateAndIsActiveTrue(hospitalId, date);
        for (Appointment appt : appointments) {
            if (appt.getDoctorId() != null) doctorIds.add(appt.getDoctorId());
            if (appt.getPatientId() != null) patientIds.add(appt.getPatientId());
        }

        // 3. IPD admissions for this date
        List<IpdAdmission> ipdAdmissions = ipdAdmissionRepository
                .findByHospitalIdAndAdmissionDatetimeGreaterThanEqualAndAdmissionDatetimeLessThan(
                        hospitalId, startOfDay, toExclusive);
        for (IpdAdmission ipd : ipdAdmissions) {
            if (ipd.getDoctorId() != null) doctorIds.add(ipd.getDoctorId());
            if (ipd.getPatientId() != null) patientIds.add(ipd.getPatientId());
        }

        // Batch fetch doctors and patients for name resolution
        Map<Long, String> doctorNameMap = new HashMap<>();
        if (!doctorIds.isEmpty()) {
            List<Doctor> doctors = doctorRepository.findAllById(doctorIds);
            for (Doctor doc : doctors) {
                doctorNameMap.put(doc.getId(), doc.getName());
            }
        }

        Map<Long, Patient> patientMap = new HashMap<>();
        if (!patientIds.isEmpty()) {
            List<Patient> patients = patientRepository.findAllById(patientIds);
            for (Patient pat : patients) {
                patientMap.put(pat.getId(), pat);
            }
        }

        // Build OPD activity entries
        for (Opd opd : opds) {
            Map<String, Object> entry = new HashMap<>();
            Patient pat = opd.getPatient() != null ? patientMap.get(opd.getPatient().getId()) : null;
            entry.put("patientId", pat != null ? (pat.getCustomId() != null ? pat.getCustomId() : pat.getPublicId()) : null);
            entry.put("patientPublicId", pat != null ? pat.getPublicId() : null);
            entry.put("patientName", pat != null ? pat.getName() : "Unknown");
            entry.put("phone", pat != null ? pat.getPhone() : null);
            entry.put(ACTIVITY_TYPE, "OPD");
            entry.put("activityTime", opd.getCreatedAt());
            entry.put("doctorName", opd.getDoctor() != null ? doctorNameMap.getOrDefault(opd.getDoctor().getId(), "Unknown") : "N/A");
            entry.put("details", opd.getCaseId());
            entry.put("status", opd.getStatus() != null ? opd.getStatus().toString() : null);
            activities.add(entry);
        }

        // Build Appointment activity entries
        for (Appointment appt : appointments) {
            Map<String, Object> entry = new HashMap<>();
            Patient pat = appt.getPatientId() != null ? patientMap.get(appt.getPatientId()) : null;
            entry.put("patientId", pat != null ? (pat.getCustomId() != null ? pat.getCustomId() : pat.getPublicId()) : null);
            entry.put("patientPublicId", pat != null ? pat.getPublicId() : null);
            entry.put("patientName", appt.getPatientName() != null ? appt.getPatientName() : (pat != null ? pat.getName() : "Unknown"));
            entry.put("phone", pat != null ? pat.getPhone() : appt.getPatientPhone());
            entry.put(ACTIVITY_TYPE, "APPOINTMENT");
            // Combine date + time for sorting
            LocalDateTime activityTime = appt.getAppointmentTime() != null
                    ? appt.getAppointmentDate().atTime(appt.getAppointmentTime())
                    : appt.getAppointmentDate().atStartOfDay();
            entry.put("activityTime", activityTime);
            entry.put("doctorName", appt.getDoctorName() != null ? appt.getDoctorName() : doctorNameMap.getOrDefault(appt.getDoctorId(), "Unknown"));
            entry.put("details", appt.getCustomId() != null ? appt.getCustomId() : appt.getPublicId());
            entry.put("status", appt.getStatus());
            activities.add(entry);
        }

        // Build IPD activity entries
        for (IpdAdmission ipd : ipdAdmissions) {
            Map<String, Object> entry = new HashMap<>();
            Patient pat = patientMap.get(ipd.getPatientId());
            entry.put("patientId", pat != null ? (pat.getCustomId() != null ? pat.getCustomId() : pat.getPublicId()) : null);
            entry.put("patientPublicId", pat != null ? pat.getPublicId() : null);
            entry.put("patientName", pat != null ? pat.getName() : "Unknown");
            entry.put("phone", pat != null ? pat.getPhone() : null);
            entry.put(ACTIVITY_TYPE, "IPD");
            entry.put("activityTime", ipd.getAdmissionDatetime());
            entry.put("doctorName", doctorNameMap.getOrDefault(ipd.getDoctorId(), "Unknown"));
            entry.put("details", ipd.getIpdNumber());
            entry.put("status", ipd.getStatus());
            activities.add(entry);
        }

        // Sort by activityTime descending (latest first)
        activities.sort((a, b) -> {
            Object timeA = a.get("activityTime");
            Object timeB = b.get("activityTime");
            if (timeA == null && timeB == null) return 0;
            if (timeA == null) return 1;
            if (timeB == null) return -1;
            LocalDateTime dtA = (LocalDateTime) timeA;
            LocalDateTime dtB = (LocalDateTime) timeB;
            return dtB.compareTo(dtA);
        });

        return activities;
    }

    public Map<String, Object> getAnalytics(Long hospitalId) {
        Map<String, Object> analytics = new HashMap<>();

        // 1. KPI cards:
        long totalPatients = patientRepository.countByHospitalIdAndIsActiveTrue(hospitalId);
        analytics.put("totalPatients", totalPatients);

        long totalDoctors = doctorRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(hospitalId).size();
        analytics.put("totalDoctors", totalDoctors);

        List<Bed> beds = bedRepository.findByHospitalId(hospitalId);
        long totalBeds = beds.size();
        long occupiedBeds = beds.stream().filter(b -> "occupied".equalsIgnoreCase(b.getStatus())).count();
        double bedOccupancyRate = totalBeds > 0 ? ((double) occupiedBeds / totalBeds) * 100.0 : 0.0;
        analytics.put("totalBeds", totalBeds);
        analytics.put("occupiedBeds", occupiedBeds);
        analytics.put("bedOccupancyRate", Math.round(bedOccupancyRate * 10.0) / 10.0);

        LocalDate today = LocalDate.now();
        LocalDate startMonthDate = today.minusMonths(5).withDayOfMonth(1);
        LocalDateTime rangeStart = startMonthDate.atStartOfDay();
        LocalDateTime rangeEnd = LocalDateTime.now();

        List<Opd> opdList = opdRepository.searchByHospitalAndDateRange(
                hospitalId, null, rangeStart, rangeEnd, null, PageRequest.of(0, 100000)).getContent();
        analytics.put("totalOPDConsultations", opdList.size());

        List<IpdAdmission> ipdList = ipdAdmissionRepository.findByHospitalIdAndAdmissionDatetimeBetween(hospitalId, rangeStart, rangeEnd);
        analytics.put("totalIPDAdmissions", ipdList.size());

        List<Billing> billingList = billingRepository.findByHospitalIdAndCreatedAtAfter(hospitalId, rangeStart);
        double totalBillingRevenue = billingList.stream()
                .filter(b -> "PAID".equalsIgnoreCase(b.getPaymentStatus()))
                .mapToDouble(b -> b.getAmount() != null ? b.getAmount().doubleValue() : 0.0)
                .sum();

        List<PharmacySale> pharmacySalesList = pharmacySaleRepository.findByHospitalIdAndCreatedAtAfter(hospitalId, rangeStart);
        double totalPharmacyRevenue = pharmacySalesList.stream()
                .filter(s -> "PAID".equalsIgnoreCase(s.getPaymentStatus()))
                .mapToDouble(s -> s.getNetAmount() != null ? s.getNetAmount().doubleValue() : 0.0)
                .sum();

        analytics.put("totalBillingRevenue", totalBillingRevenue);
        analytics.put("totalPharmacyRevenue", totalPharmacyRevenue);
        analytics.put("totalRevenue", Math.round((totalBillingRevenue + totalPharmacyRevenue) * 100.0) / 100.0);

        List<Appointment> appointmentList = appointmentRepository.findByHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(hospitalId);
        List<Appointment> appointmentsInRange = appointmentList.stream()
                .filter(a -> !a.getAppointmentDate().isBefore(startMonthDate))
                .collect(Collectors.toList());

        long completedAppts = appointmentsInRange.stream().filter(a -> "COMPLETED".equalsIgnoreCase(a.getStatus())).count();
        long pendingAppts = appointmentsInRange.stream().filter(a -> "PENDING".equalsIgnoreCase(a.getStatus()) || "CONFIRMED".equalsIgnoreCase(a.getStatus())).count();
        long cancelledAppts = appointmentsInRange.stream().filter(a -> "CANCELLED".equalsIgnoreCase(a.getStatus())).count();

        List<Map<String, Object>> appointmentStatusData = new ArrayList<>();
        Map<String, Object> compMap = new HashMap<>(); compMap.put("name", "Completed"); compMap.put("value", completedAppts); appointmentStatusData.add(compMap);
        Map<String, Object> pendMap = new HashMap<>(); pendMap.put("name", "Pending"); pendMap.put("value", pendingAppts); appointmentStatusData.add(pendMap);
        Map<String, Object> cancMap = new HashMap<>(); cancMap.put("name", "Cancelled"); cancMap.put("value", cancelledAppts); appointmentStatusData.add(cancMap);
        analytics.put("appointmentStatus", appointmentStatusData);

        long totalAppts = completedAppts + pendingAppts + cancelledAppts;
        double fulfillmentRate = totalAppts > 0 ? ((double) completedAppts / totalAppts) * 100.0 : 0.0;
        analytics.put("fulfillmentRate", Math.round(fulfillmentRate * 10.0) / 10.0);

        List<Map<String, Object>> monthlyTrends = new ArrayList<>();
        DateTimeFormatter trendMonthFormatter = DateTimeFormatter.ofPattern("yyyy-MM");
        DateTimeFormatter displayMonthFormatter = DateTimeFormatter.ofPattern("MMM yyyy");

        for (int i = 5; i >= 0; i--) {
            LocalDate mDate = today.minusMonths(i);
            String yyyyMM = mDate.format(trendMonthFormatter);
            String displayMonth = mDate.format(displayMonthFormatter);

            long opdCount = opdList.stream()
                    .filter(o -> o.getCreatedAt() != null && o.getCreatedAt().format(trendMonthFormatter).equals(yyyyMM))
                    .count();

            long ipdCount = ipdList.stream()
                    .filter(ipd -> ipd.getAdmissionDatetime() != null && ipd.getAdmissionDatetime().format(trendMonthFormatter).equals(yyyyMM))
                    .count();

            double billRev = billingList.stream()
                    .filter(b -> "PAID".equalsIgnoreCase(b.getPaymentStatus()) && b.getCreatedAt() != null && b.getCreatedAt().format(trendMonthFormatter).equals(yyyyMM))
                    .mapToDouble(b -> b.getAmount() != null ? b.getAmount().doubleValue() : 0.0)
                    .sum();

            double pharmRev = pharmacySalesList.stream()
                    .filter(s -> "PAID".equalsIgnoreCase(s.getPaymentStatus()) && s.getCreatedAt() != null && s.getCreatedAt().format(trendMonthFormatter).equals(yyyyMM))
                    .mapToDouble(s -> s.getNetAmount() != null ? s.getNetAmount().doubleValue() : 0.0)
                    .sum();

            Map<String, Object> trendItem = new HashMap<>();
            trendItem.put("month", displayMonth);
            trendItem.put("opdCount", opdCount);
            trendItem.put("ipdCount", ipdCount);
            trendItem.put("billingRevenue", Math.round(billRev * 100.0) / 100.0);
            trendItem.put("pharmacyRevenue", Math.round(pharmRev * 100.0) / 100.0);
            trendItem.put("totalRevenue", Math.round((billRev + pharmRev) * 100.0) / 100.0);
            monthlyTrends.add(trendItem);
        }
        analytics.put("monthlyTrends", monthlyTrends);

        Map<Long, String> doctorNameMap = new HashMap<>();
        List<Doctor> activeDoctors = doctorRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(hospitalId);
        for (Doctor doc : activeDoctors) {
            doctorNameMap.put(doc.getId(), doc.getName());
        }

        Map<String, Long> docCounts = new HashMap<>();
        for (Opd opd : opdList) {
            if (opd.getDoctor() != null) {
                String docName = doctorNameMap.getOrDefault(opd.getDoctor().getId(), opd.getDoctor().getName());
                docCounts.put(docName, docCounts.getOrDefault(docName, 0L) + 1);
            }
        }

        List<Map<String, Object>> doctorWorkload = new ArrayList<>();
        docCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("doctorName", entry.getKey());
                    item.put("consultations", entry.getValue());
                    doctorWorkload.add(item);
                });
        analytics.put("doctorWorkload", doctorWorkload);

        List<Ward> wards = wardRepository.findByHospitalId(hospitalId);
        List<Map<String, Object>> wardOccupancyInfo = new ArrayList<>();
        for (Ward ward : wards) {
            long totalWardBeds = beds.stream().filter(b -> b.getWardId().equals(ward.getWardId())).count();
            long occupiedWardBeds = beds.stream()
                    .filter(b -> b.getWardId().equals(ward.getWardId()) && "occupied".equalsIgnoreCase(b.getStatus()))
                    .count();

            Map<String, Object> wMap = new HashMap<>();
            wMap.put("wardName", ward.getWardName());
            wMap.put("totalBeds", totalWardBeds);
            wMap.put("occupiedBeds", occupiedWardBeds);
            wMap.put("availableBeds", totalWardBeds - occupiedWardBeds);
            wMap.put("occupancyRate", totalWardBeds > 0 ? Math.round(((double) occupiedWardBeds / totalWardBeds) * 100.0 * 10.0) / 10.0 : 0.0);
            wardOccupancyInfo.add(wMap);
        }
        analytics.put("wardOccupancy", wardOccupancyInfo);

        return analytics;
    }

    @CacheEvict(value = "hospitalStats", key = "#hospitalId")
    public void evictStats(Long hospitalId) {
        // Handled automatically by Spring Cache eviction mechanism
    }
}

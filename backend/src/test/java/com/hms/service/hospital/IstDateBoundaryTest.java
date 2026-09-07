package com.hms.service.hospital;

import com.hms.entity.IpdAdmission;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import com.hms.service.PdfService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Real repository predicates and service boundaries, with a fresh cache for every test. */
@DataJpaTest(showSql = false, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({HospitalStatsService.class, PatientService.class, IstDateBoundaryTest.Caching.class})
class IstDateBoundaryTest {
    static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    static final long HOSPITAL = 81001L;
    static final List<LocalDateTime> INCLUDED = List.of(
            DAY.atStartOfDay(), DAY.atTime(5, 0), DAY.atTime(18, 29, 59),
            DAY.atTime(18, 30), DAY.atTime(23, 59, 59, 999999000));
    static final List<LocalDateTime> EXCLUDED = List.of(
            DAY.minusDays(1).atTime(18, 30), DAY.minusDays(1).atTime(23, 59, 59),
            DAY.plusDays(1).atStartOfDay());

    @Autowired HospitalStatsService stats;
    @Autowired PatientService patients;
    @Autowired PatientRepository patientRepository;
    @Autowired OpdRepository opdRepository;
    @Autowired IpdAdmissionRepository ipdRepository;
    @Autowired EntityManager em;
    @Autowired CacheManager cacheManager;
    @MockBean BusinessClock businessClock;
    @MockBean DoctorService doctors;
    @MockBean AppointmentService appointments;
    @MockBean SecurityContextHelper security;
    @MockBean AuditLogService audit;
    @MockBean HospitalWebSocketHandler websocket;
    @MockBean PdfService pdf;

    @TestConfiguration
    @EnableCaching
    static class Caching {
        @Bean CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("hospitalStats");
        }
    }

    @BeforeEach
    void setUp() {
        cacheManager.getCache("hospitalStats").clear();
        when(businessClock.today()).thenReturn(DAY);
        when(security.getCurrentHospitalId()).thenReturn(HOSPITAL);
        when(doctors.getAllDoctors(any())).thenReturn(Page.empty());
    }

    @Test
    void statsUseWholeBusinessDayAndWholeBusinessMonthWithoutUtcShift() {
        seedPatients();
        patient(HOSPITAL, DAY.withDayOfMonth(1).atStartOfDay(), true);
        patient(HOSPITAL, DAY.withDayOfMonth(1).minusDays(1).atTime(23, 59, 59), true);
        Map<String, Long> result = stats.getStats(HOSPITAL);
        assertThat(result.get("patientsToday")).isEqualTo(5);
        // The month is the whole calendar month [Sep 1, Oct 1), not month-to-date: five on the
        // 6th, two on the 5th, one on the 1st, and the 7th-of-September fixture that the day
        // boundary excludes but September plainly contains. The last of those cannot occur in
        // production — @CreationTimestamp cannot write a future row — it exists here only
        // because the fixtures set created_at directly.
        assertThat(result.get("patientsThisMonth")).isEqualTo(9);
        // The August fixture stays out, so the lower month bound is a real boundary too.
    }

    static java.util.stream.Stream<Arguments> boundaryCases() {
        return java.util.stream.Stream.concat(
                INCLUDED.stream().map(time -> Arguments.of(time, 1L)),
                EXCLUDED.stream().map(time -> Arguments.of(time, 0L)));
    }

    @ParameterizedTest
    @MethodSource("boundaryCases")
    void todayCountChecksEachBoundaryIndependently(LocalDateTime time, long expected) {
        patient(HOSPITAL, time, true);
        assertThat(stats.getStats(HOSPITAL).get("patientsToday")).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void patientTodayAndExplicitDateReturnExactlyTheBusinessDay(boolean explicitDate) {
        seedPatients();
        // Explicit date wins even when business today differs; view matching is case-insensitive.
        if (explicitDate) when(businessClock.today()).thenReturn(DAY.plusDays(2));
        Page<Patient> result = patients.getAllPatients(null, "ToDaY",
                explicitDate ? DAY : null, PageRequest.of(0, 100));
        assertThat(result.getContent()).extracting(Patient::getCreatedAt)
                .containsExactlyElementsOf(INCLUDED.stream().sorted(java.util.Comparator.reverseOrder()).toList());
        assertThat(result.getTotalElements()).isEqualTo(5);
    }

    @Test
    void patientPaginationCountsUseTheSameExclusiveEnd() {
        seedPatients();
        Page<Patient> page = patients.getAllPatients(null, null, DAY, PageRequest.of(0, 2));
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).extracting(Patient::getCreatedAt)
                .containsExactly(INCLUDED.get(4), INCLUDED.get(3));
    }

    @Test
    void activityExplicitDateFiltersBothOpdAndIpdWithTenantIsolation() {
        Patient owner = patient(HOSPITAL, DAY.atStartOfDay(), true);
        List<LocalDateTime> all = new ArrayList<>(INCLUDED);
        all.addAll(EXCLUDED);
        for (LocalDateTime time : all) activity(owner, time);
        activity(patient(HOSPITAL + 1, DAY.atStartOfDay(), true), DAY.atTime(12, 0));
        em.flush();
        em.clear();
        List<Map<String, Object>> result = stats.getPatientActivityByDate(HOSPITAL, DAY);
        assertThat(result).hasSize(10);
        for (String type : List.of("OPD", "IPD")) {
            assertThat(result.stream().filter(row -> type.equals(row.get("activityType")))
                    .map(row -> (LocalDateTime) row.get("activityTime")).toList())
                    .containsExactlyElementsOf(INCLUDED.stream().sorted(java.util.Comparator.reverseOrder()).toList());
        }
    }

    @Test
    void existingInclusiveActivityFindersKeepTheirOtherCallersSemantics() {
        LocalDateTime end = DAY.plusDays(1).atStartOfDay();
        activity(patient(HOSPITAL, DAY.atStartOfDay(), true), end);
        em.flush();
        assertThat(opdRepository.searchByHospitalAndDateRange(HOSPITAL, null,
                DAY.atStartOfDay(), end, null, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(ipdRepository.findByHospitalIdAndAdmissionDatetimeBetween(
                HOSPITAL, DAY.atStartOfDay(), end)).hasSize(1);
        assertThat(opdRepository.findActivityInDateRange(HOSPITAL,
                DAY.atStartOfDay(), end, PageRequest.of(0, 10))).isEmpty();
        assertThat(ipdRepository.findByHospitalIdAndAdmissionDatetimeGreaterThanEqualAndAdmissionDatetimeLessThan(
                HOSPITAL, DAY.atStartOfDay(), end)).isEmpty();
    }

    @Test
    void statsCacheHitAndEvictionAreExercisedThroughSpringProxy() {
        assertThat(AopUtils.isAopProxy(stats)).isTrue();
        seedPatients();
        assertThat(stats.getStats(HOSPITAL).get("patientsToday")).isEqualTo(5);
        patient(HOSPITAL, DAY.atTime(22, 0), true);
        assertThat(stats.getStats(HOSPITAL).get("patientsToday")).isEqualTo(5);
        stats.evictStats(HOSPITAL);
        assertThat(cacheManager.getCache("hospitalStats").get(HOSPITAL)).isNull();
        assertThat(stats.getStats(HOSPITAL).get("patientsToday")).isEqualTo(6);
    }

    private void seedPatients() {
        INCLUDED.forEach(time -> patient(HOSPITAL, time, true));
        EXCLUDED.forEach(time -> patient(HOSPITAL, time, true));
        patient(HOSPITAL, DAY.atTime(12, 0), false);
        patient(HOSPITAL + 1, DAY.atTime(12, 0), true);
        em.clear();
    }

    private Patient patient(long hospital, LocalDateTime time, boolean active) {
        Patient p = new Patient();
        p.setHospitalId(hospital);
        p.setName("Boundary Patient");
        p.setPhone("9876543210");
        p.setGender("MALE");
        p.setIsActive(active);
        p = patientRepository.saveAndFlush(p);
        // @CreationTimestamp overwrites application timestamps. Set independent persisted fixtures.
        em.createNativeQuery("UPDATE patients SET created_at = :time WHERE id = :id")
                .setParameter("time", time).setParameter("id", p.getId()).executeUpdate();
        em.refresh(p);
        return p;
    }

    private void activity(Patient owner, LocalDateTime time) {
        Opd opd = new Opd();
        opd.setPatient(owner);
        opd.setCreatedAt(time);
        opdRepository.save(opd);
        IpdAdmission ipd = new IpdAdmission();
        ipd.setIpdNumber("IPD-" + UUID.randomUUID());
        ipd.setHospitalId(owner.getHospitalId());
        ipd.setPatientId(owner.getId());
        ipd.setDoctorId(1L);
        ipd.setWardId(1L);
        ipd.setBedId(1L);
        ipd.setAdmissionType("ELECTIVE");
        ipd.setStatus("ADMITTED");
        ipd.setAdmissionDatetime(time);
        ipdRepository.save(ipd);
    }
}

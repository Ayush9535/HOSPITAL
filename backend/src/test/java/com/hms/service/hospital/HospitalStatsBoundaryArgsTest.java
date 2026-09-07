package com.hms.service.hospital;

import com.hms.repository.PatientRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The boundary values themselves, captured as the service hands them to the repository.
 *
 * <p>The sibling {@code IstDateBoundaryTest} proves the same fix through a real database, which is
 * the stronger evidence. This one exists because the defect was never about which rows came back —
 * it was about two LocalDateTime values being silently shifted 5h30m before they ever reached SQL.
 * Asserting them directly makes a regression name itself: a reintroduced
 * {@code withZoneSameInstant(UTC)} fails here with "expected 2026-09-06T00:00 but was
 * 2026-09-05T18:30", which is the bug written out in full.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HospitalStatsBoundaryArgsTest {

    private static final long HOSPITAL = 4242L;
    /** A Sunday mid-month, so the month bound cannot accidentally coincide with the day bound. */
    private static final LocalDate BUSINESS_DAY = LocalDate.of(2026, 9, 6);

    @Mock BusinessClock businessClock;
    @Mock PatientRepository patientRepository;
    @Mock PatientService patientService;
    @Mock DoctorService doctorService;
    @Mock AppointmentService appointmentService;
    @Mock SecurityContextHelper securityHelper;

    @InjectMocks HospitalStatsService stats;

    private ArgumentCaptor<LocalDateTime> from;
    private ArgumentCaptor<LocalDateTime> toExclusive;

    private void runStats() {
        when(businessClock.today()).thenReturn(BUSINESS_DAY);
        when(patientService.getPatientCount()).thenReturn(0L);
        when(doctorService.getAllDoctors(any())).thenReturn(Page.empty());
        when(appointmentService.getTodaysAppointmentsCount()).thenReturn(0L);

        stats.getStats(HOSPITAL);

        from = ArgumentCaptor.forClass(LocalDateTime.class);
        toExclusive = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(patientRepository, times(2))
                .countActiveInDateRange(eq(HOSPITAL), from.capture(), toExclusive.capture());
    }

    @Test
    void todayWindowIsTheIstCalendarDayHalfOpen() {
        runStats();

        assertThat(from.getAllValues().get(0)).isEqualTo(LocalDateTime.of(2026, 9, 6, 0, 0));
        assertThat(toExclusive.getAllValues().get(0)).isEqualTo(LocalDateTime.of(2026, 9, 7, 0, 0));
    }

    @Test
    void monthWindowIsTheWholeIstCalendarMonthHalfOpen() {
        runStats();

        assertThat(from.getAllValues().get(1)).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThat(toExclusive.getAllValues().get(1)).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
    }

    /**
     * The defect, stated as an assertion. The old code produced 2026-09-05T18:30 →
     * 2026-09-06T18:29:59.999999999 for this business day, so "today" ran from half past six the
     * previous evening and stopped counting at half past six this evening.
     */
    @Test
    void theOldUtcShiftedWindowIsNeverConstructed() {
        runStats();

        assertThat(from.getAllValues())
                .as("no boundary may be shifted back into the previous evening")
                .doesNotContain(LocalDateTime.of(2026, 9, 5, 18, 30));
        assertThat(toExclusive.getAllValues())
                .as("no boundary may end at 18:29:59.999999999")
                .doesNotContain(LocalDateTime.of(2026, 9, 6, 18, 29, 59, 999_999_999));

        assertThat(from.getAllValues()).allSatisfy(v ->
                assertThat(v.toLocalTime()).as("every bound is midnight").isEqualTo(java.time.LocalTime.MIDNIGHT));
        assertThat(toExclusive.getAllValues()).allSatisfy(v ->
                assertThat(v.toLocalTime()).as("every bound is midnight").isEqualTo(java.time.LocalTime.MIDNIGHT));
    }
}

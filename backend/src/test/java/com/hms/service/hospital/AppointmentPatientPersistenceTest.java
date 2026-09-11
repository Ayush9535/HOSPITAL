package com.hms.service.hospital;

import com.hms.entity.Appointment;
import com.hms.entity.Doctor;
import com.hms.entity.Patient;
import com.hms.repository.DoctorRepository;
import com.hms.repository.PatientRepository;
import com.hms.security.SecurityContextHelper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Booking an appointment for someone new registers them properly.
 *
 * <p>The unit test beside this one stubs PatientService, so it can say what AppointmentService
 * hands over but nothing about what ends up in the database — and "the row has a registration
 * number" is a claim about the database. This drives the real service against the real schema and
 * reads the row back through a cleared persistence context, so the assertion is about committed
 * SQL rather than a managed entity that happens to hold the right value in memory.
 *
 * <p>Worth stating plainly: every patient booked this way used to be saved with a null customId,
 * because the number was assigned inline in addPatient and this path never went near it.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({AppointmentService.class, PatientService.class, PatientRegistrar.class})
class AppointmentPatientPersistenceTest {

    private static final long MINE = 1L;

    @Autowired AppointmentService appointmentService;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired EntityManager em;

    @MockBean SecurityContextHelper securityHelper;
    @MockBean com.hms.service.AuditLogService auditLogService;
    @MockBean com.hms.security.HospitalWebSocketHandler webSocketHandler;
    @MockBean BillingService billingService;
    @MockBean BusinessClock businessClock;
    @MockBean com.hms.service.PdfService pdfService;
    @MockBean org.springframework.cache.CacheManager cacheManager;

    private Long doctorId;

    @BeforeEach
    void setUp() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(MINE);
        when(securityHelper.getCurrentUserEmail()).thenReturn("reception@hospital.test");

        Doctor d = new Doctor();
        d.setHospitalId(MINE);
        d.setName("Dr Mandal");
        d.setEmail("mandal" + System.nanoTime() + "@hospital.test");
        d.setPhone("9800000001");
        d.setSpecialization("General");
        d.setIsActive(true);
        doctorId = doctorRepository.saveAndFlush(d).getId();
    }

    private Appointment walkIn(String phone) {
        Appointment a = new Appointment();
        a.setPatientName("Neha Kulkarni");
        a.setPatientPhone(phone);
        a.setPatientGender("FEMALE");
        a.setPatientDateOfBirth(LocalDate.of(1990, 3, 12));
        a.setDoctorId(doctorId);
        a.setAppointmentDate(LocalDate.now().plusDays(1));
        a.setAppointmentTime(LocalTime.of(10, 0));
        return a;
    }

    @Test
    void aPatientBookedThroughAnAppointmentIsNumberedInTheDatabase() {
        appointmentService.createAppointment(walkIn("9876543210"));

        em.flush();
        em.clear();

        Patient stored = patientRepository.findByPhoneAndHospitalIdAndIsActiveTrue("9876543210", MINE)
                .stream().findFirst().orElseThrow();
        assertThat(stored.getCustomId())
                .as("read back from the database, not from the instance the service returned")
                .isEqualTo("PAT" + stored.getId());
        assertThat(stored.getHospitalId()).isEqualTo(MINE);
    }

    @Test
    void anExistingPatientIsReusedAndKeepsTheNumberItAlreadyHad() {
        Patient existing = new Patient();
        existing.setHospitalId(MINE);
        existing.setName("Asha Rao");
        existing.setPhone("9990001111");
        existing.setGender("FEMALE");
        existing.setDateOfBirth(LocalDate.of(1985, 1, 1));
        Patient seeded = patientRepository.saveAndFlush(existing);
        seeded.setCustomId("PAT" + seeded.getId());
        em.flush();
        long countBefore = patientRepository.count();

        Appointment a = walkIn("9990001111");
        a.setAppointmentTime(LocalTime.of(11, 0));
        appointmentService.createAppointment(a);

        em.flush();
        em.clear();

        assertThat(patientRepository.count())
                .as("reuse means reuse: booking for a known phone creates no second identity")
                .isEqualTo(countBefore);
        Patient reloaded = patientRepository.findById(seeded.getId()).orElseThrow();
        assertThat(reloaded.getCustomId()).isEqualTo("PAT" + seeded.getId());
    }
}

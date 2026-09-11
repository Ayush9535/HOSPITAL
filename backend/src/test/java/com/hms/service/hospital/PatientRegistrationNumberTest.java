package com.hms.service.hospital;

import com.hms.entity.Patient;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Every patient leaves registration with a number.
 *
 * <p>The number is "PAT" + the auto-increment id, so it cannot be known until the row exists.
 * That forced a two-step write, and the second step lived inline in addPatient — which meant the
 * appointment booking path, which inserted its own patients, produced patients with no number at
 * all. Those patients show a raw UUID wherever the UI prints a patient id, and nothing repairs
 * them afterwards. The insert and the number now come from one shared method so neither caller
 * can have one without the other.
 *
 * <p>Against a real schema rather than a mocked repository: the number depends on a database
 * generated key, and a stubbed save proves nothing about what was actually committed.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PatientService.class, PatientRegistrar.class})
class PatientRegistrationNumberTest {

    private static final long MINE = 1L;

    @Autowired PatientService patientService;
    @Autowired PatientRegistrar patientRegistrar;
    @Autowired PatientRepository patientRepository;
    @Autowired EntityManager em;

    @MockBean SecurityContextHelper securityHelper;
    // Collaborators PatientService holds for other operations; none is on the creation path.
    @MockBean BusinessClock businessClock;
    @MockBean org.springframework.cache.CacheManager cacheManager;
    @MockBean com.hms.service.PdfService pdfService;
    @MockBean com.hms.service.AuditLogService auditLogService;
    @MockBean com.hms.security.HospitalWebSocketHandler webSocketHandler;

    @BeforeEach
    void setUp() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(MINE);
        when(securityHelper.getCurrentUserEmail()).thenReturn("reception@hospital.test");
    }

    private Patient valid(String phone) {
        Patient p = new Patient();
        p.setName("Neha Kulkarni");
        p.setPhone(phone);
        p.setGender("FEMALE");
        p.setDateOfBirth(LocalDate.of(1990, 3, 12));
        return p;
    }

    // ── the registration endpoint ────────────────────────────────────────────

    @Test
    void aRegisteredPatientGetsPatPlusItsOwnId() {
        Patient saved = patientService.addPatient(valid("9876543210"));
        em.flush();
        em.clear();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCustomId()).isEqualTo("PAT" + saved.getId());

        // And it is on the row, not merely on the returned instance.
        Patient reloaded = patientRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCustomId()).isEqualTo("PAT" + saved.getId());
    }

    // ── the shared insert, which the appointment path uses ───────────────────

    @Test
    void theSharedInsertNumbersThePatientTheSameWay() {
        // AppointmentService assigns the tenant itself and then calls this, exactly as here.
        Patient p = valid("9876543211");
        p.setHospitalId(MINE);

        Patient saved = patientRegistrar.persistNewPatient(p);
        em.flush();
        em.clear();

        assertThat(saved.getCustomId())
                .as("an appointment-booked patient is numbered like any other")
                .isEqualTo("PAT" + saved.getId());
        assertThat(patientRepository.findById(saved.getId()).orElseThrow().getCustomId())
                .isEqualTo("PAT" + saved.getId());
    }

    @Test
    void bothPathsProduceTheSameShapeOfNumber() {
        Patient viaDesk = patientService.addPatient(valid("9876543212"));

        Patient viaBooking = valid("9876543213");
        viaBooking.setHospitalId(MINE);
        Patient booked = patientRegistrar.persistNewPatient(viaBooking);
        em.flush();

        assertThat(viaDesk.getCustomId()).startsWith("PAT");
        assertThat(booked.getCustomId()).startsWith("PAT");
        assertThat(booked.getCustomId()).isNotEqualTo(viaDesk.getCustomId());
    }

    // ── atomicity ────────────────────────────────────────────────────────────

    @Test
    void aRejectedRegistrationCommitsNoPatientAtAll() {
        long before = patientRepository.count();

        // Rejected after the tenant is resolved but before any insert: DOB in the future.
        Patient bad = valid("9876543214");
        bad.setDateOfBirth(LocalDate.now().plusYears(1));
        assertThatThrownBy(() -> patientService.addPatient(bad))
                .isInstanceOf(IllegalArgumentException.class);
        em.clear();

        assertThat(patientRepository.count())
                .as("nothing half-created survives a rejected registration")
                .isEqualTo(before);
    }

    @Test
    void noPatientIsEverCommittedWithoutANumber() {
        patientService.addPatient(valid("9876543215"));
        Patient booked = valid("9876543216");
        booked.setHospitalId(MINE);
        patientRegistrar.persistNewPatient(booked);
        em.flush();
        em.clear();

        assertThat(patientRepository.findAll())
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.getCustomId()).isNotBlank());
    }

    // ── tenancy is unchanged ─────────────────────────────────────────────────

    @Test
    void theTenantStillComesFromTheSessionAndOverwritesTheClient() {
        Patient claimingAnotherTenant = valid("9876543217");
        claimingAnotherTenant.setHospitalId(999L);

        Patient saved = patientService.addPatient(claimingAnotherTenant);
        em.flush();

        assertThat(saved.getHospitalId())
                .as("a client cannot register a patient into someone else's hospital")
                .isEqualTo(MINE);
    }
}

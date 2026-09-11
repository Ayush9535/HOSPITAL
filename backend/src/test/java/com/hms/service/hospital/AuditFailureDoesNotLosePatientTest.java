package com.hms.service.hospital;

import com.hms.entity.Patient;
import com.hms.repository.AuditLogRepository;
import com.hms.repository.PatientRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A failing audit write must not take the patient down with it.
 *
 * <p>Deliberately NOT a @DataJpaTest: that wraps each test in a transaction it rolls back, which
 * is precisely the thing under examination. Here the service is called outside any test
 * transaction, so addPatient's own boundary commits for real and the patient is read back
 * afterwards in a separate transaction.
 *
 * <p>The audit service itself is real, not mocked. That matters: AuditLogService.logAction is
 * @Transactional and public on a @Service, so the failure has to travel out through its proxy to
 * reproduce what production would do. Mocking the service would remove the very proxy that
 * decides whether the outer transaction is marked rollback-only, and the test would prove
 * nothing. The repository underneath it is what fails.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditFailureDoesNotLosePatientTest {

    private static final long MINE = 4242L;
    private static final String PHONE = "9812345678";

    @Autowired PatientService patientService;
    @Autowired PatientRepository patientRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @MockBean SecurityContextHelper securityHelper;
    @MockBean AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(MINE);
        when(securityHelper.getCurrentUserEmail()).thenReturn("reception@hospital.test");
        when(securityHelper.getCurrentUserRole()).thenReturn("RECEPTIONIST");
        // The shape a real audit failure takes: the insert itself blows up.
        when(auditLogRepository.save(any())).thenThrow(new RuntimeException("audit sink unavailable"));
    }

    @AfterEach
    void cleanUp() {
        // This test commits for real, so it has to clear up after itself.
        transactionTemplate.executeWithoutResult(s -> {
            List<Patient> mine = patientRepository.findByPhoneAndHospitalIdAndIsActiveTrue(PHONE, MINE);
            patientRepository.deleteAll(mine);
        });
    }

    /**
     * Registration survives an audit outage.
     *
     * <p>This is a regression test for a real one: giving addPatient its own @Transactional made
     * the patient and the audit share a transaction, and AuditLogService.logAction is itself
     * transactional — so a failing audit marked the shared transaction rollback-only and the
     * commit threw UnexpectedRollbackException with zero patients written, even though addPatient
     * caught the exception and appeared to succeed. Measured before the fix: threw=
     * UnexpectedRollbackException, committedRows=0.
     */
    @Test
    void aFailedAuditWriteDoesNotCostTheHospitalThePatient() {
        Patient p = new Patient();
        p.setName("Neha Kulkarni");
        p.setPhone(PHONE);
        p.setGender("FEMALE");
        p.setDateOfBirth(LocalDate.of(1990, 3, 12));

        Throwable thrown = catchThrowable(() -> patientService.addPatient(p));

        // Read in a fresh transaction: what actually committed, not what the call returned.
        List<Patient> committed = transactionTemplate.execute(
                s -> patientRepository.findByPhoneAndHospitalIdAndIsActiveTrue(PHONE, MINE));

        // The product's stated intent: an audit hiccup must not cost the hospital the patient.
        assertThat(thrown)
                .as("registration must survive an audit failure")
                .isNull();
        assertThat(committed)
                .as("the patient must be committed despite the audit failure")
                .hasSize(1);
        assertThat(committed.get(0).getCustomId())
                .isEqualTo("PAT" + committed.get(0).getId());
    }
}

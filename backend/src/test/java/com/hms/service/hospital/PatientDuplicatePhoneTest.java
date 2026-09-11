package com.hms.service.hospital;

import com.hms.dto.DuplicatePatientMatch;
import com.hms.entity.Patient;
import com.hms.exception.DuplicatePhoneConflictException;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase A of patient duplicate prevention: a phone number already registered at this hospital
 * stops a registration and asks, instead of silently producing a second record or silently
 * reusing the first.
 *
 * <p>The rule being defended is narrow and deliberate. A phone number is a <b>lookup key, not an
 * identity</b>. A parent and a child legitimately share one mobile, so the system may not decide
 * on its own which of them an appointment is for — and an inactive patient may not hold a real
 * person's number hostage, since nothing in this system can bring a soft-deleted patient back.
 *
 * <p>Against a real schema rather than a mocked repository: the behaviour under test is which
 * rows exist afterwards.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PatientService.class, PatientRegistrar.class, PatientDuplicateFinder.class})
class PatientDuplicatePhoneTest {

    private static final long MINE = 1L;
    private static final long THEIRS = 2L;
    private static final String SHARED = "9876500001";

    @Autowired PatientService patientService;
    @Autowired PatientDuplicateFinder finder;
    @Autowired PatientRepository patientRepository;
    @Autowired EntityManager em;

    @MockBean SecurityContextHelper securityHelper;
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

    private Patient patient(String name, String phone, LocalDate dob) {
        Patient p = new Patient();
        p.setName(name);
        p.setPhone(phone);
        p.setGender("MALE");
        p.setDateOfBirth(dob);
        return p;
    }

    private Patient parent() {
        return patient("Rahul Patil", SHARED, LocalDate.of(1987, 4, 2));
    }

    private Patient child() {
        return patient("Aarav Patil", SHARED, LocalDate.of(2017, 9, 21));
    }

    private long countMine() {
        return patientRepository.countByHospitalId(MINE);
    }

    // ── the ordinary case ────────────────────────────────────────────────────

    @Test
    void aFreeNumberRegistersWithNoQuestionAsked() {
        Patient saved = patientService.addPatient(parent());
        em.flush();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getDuplicatePhoneAckFor()).isNull();
        assertThat(countMine()).isEqualTo(1);
    }

    // ── one match is still a question, not an answer ─────────────────────────

    /**
     * The case the whole checkpoint exists for. One match is exactly where reusing looks safest,
     * and it is the case where guessing wrong attaches a child's record to the parent's.
     */
    @Test
    void aSingleMatchConflictsRatherThanReusingThatPatient() {
        Patient existing = patientService.addPatient(parent());
        em.flush();

        assertThatThrownBy(() -> patientService.addPatient(child()))
                .isInstanceOf(DuplicatePhoneConflictException.class);

        assertThat(countMine()).isEqualTo(1);
        assertThat(patientRepository.findById(existing.getId())).isPresent();
    }

    @Test
    void theConflictCarriesEveryMatchInRegistrationOrder() {
        Patient first = patientService.addPatient(parent());
        Patient second = patientService.addPatient(child(), true);
        em.flush();

        DuplicatePhoneConflictException thrown = (DuplicatePhoneConflictException)
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> patientService.addPatient(patient("Third", SHARED, LocalDate.of(1960, 1, 1))));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getConflicts())
                .extracting(DuplicatePatientMatch::id)
                .containsExactly(first.getId(), second.getId());
    }

    /**
     * A conflict body is shown to staff, so it carries what identifies a person to a human and
     * nothing else. The phone is excluded because the caller just typed it; the full date of
     * birth because the age already settles parent-versus-child.
     */
    @Test
    void theConflictExposesOnlyTheApprovedFields() {
        Patient existing = patientService.addPatient(parent());
        em.flush();

        List<DuplicatePatientMatch> conflicts =
                finder.findActiveByPhone(MINE, SHARED, null);

        assertThat(conflicts).hasSize(1);
        DuplicatePatientMatch match = conflicts.get(0);
        assertThat(match.id()).isEqualTo(existing.getId());
        assertThat(match.publicId()).isEqualTo(existing.getPublicId());
        assertThat(match.customId()).isEqualTo(existing.getCustomId());
        assertThat(match.name()).isEqualTo("Rahul Patil");
        assertThat(match.age()).isEqualTo(existing.getAge());

        assertThat(DuplicatePatientMatch.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder("id", "publicId", "customId", "name", "age");
    }

    // ── the acknowledged family ──────────────────────────────────────────────

    @Test
    void anAcknowledgedDifferentPersonIsRegisteredAlongsideTheFirst() {
        patientService.addPatient(parent());
        Patient saved = patientService.addPatient(child(), true);
        em.flush();

        assertThat(countMine()).isEqualTo(2);
        assertThat(saved.getDuplicatePhoneAckFor()).isEqualTo(SHARED);
        assertThat(saved.getDuplicatePhoneAckBy()).isEqualTo("reception@hospital.test");
        assertThat(saved.getDuplicatePhoneAckAt()).isNotNull();
    }

    @Test
    void aThirdFamilyMemberCanAlsoBeAcknowledged() {
        patientService.addPatient(parent());
        patientService.addPatient(child(), true);
        patientService.addPatient(patient("Sunita Patil", SHARED, LocalDate.of(1960, 2, 2)), true);
        em.flush();

        assertThat(countMine()).isEqualTo(3);
    }

    @Test
    void acknowledgingIsAudited() {
        patientService.addPatient(parent());
        patientService.addPatient(child(), true);

        verify(auditLogService).logAction(
                eq("PATIENT_DUPLICATE_PHONE_ACKNOWLEDGED"),
                anyString(), anyString(), anyLong(), eq("PATIENT"), anyString(), anyString());
    }

    /** An audit trail should reconcile a record against a number, not be a contact list. */
    @Test
    void theAuditMasksTheNumber() {
        patientService.addPatient(parent());
        patientService.addPatient(child(), true);

        org.mockito.ArgumentCaptor<String> details = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditLogService).logAction(
                eq("PATIENT_DUPLICATE_PHONE_ACKNOWLEDGED"),
                details.capture(), anyString(), anyLong(), eq("PATIENT"), anyString(), anyString());

        assertThat(details.getValue()).contains("98******01").doesNotContain(SHARED);
    }

    @Test
    void anUncontestedRegistrationIsNotAudited() {
        patientService.addPatient(parent());

        verify(auditLogService, never()).logAction(
                eq("PATIENT_DUPLICATE_PHONE_ACKNOWLEDGED"),
                anyString(), anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    // ── who does NOT reserve a number ────────────────────────────────────────

    /**
     * Soft delete is an undo for mis-registration, not a claim on a phone number. Nothing in this
     * system reactivates a patient, so letting a deleted row hold a number would deny a real
     * person registration with no way back.
     */
    @Test
    void anInactivePatientDoesNotReserveTheNumber() {
        Patient gone = patientService.addPatient(parent());
        em.flush();
        gone.setIsActive(false);
        patientRepository.saveAndFlush(gone);

        Patient fresh = patientService.addPatient(child());
        em.flush();

        assertThat(fresh.getId()).isNotNull();
        assertThat(fresh.getDuplicatePhoneAckFor()).isNull();
        assertThat(countMine()).isEqualTo(2);
    }

    /** Families move between providers. A number shared across hospitals is not a duplicate. */
    @Test
    void theSameNumberAtAnotherHospitalIsNotAConflict() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(THEIRS);
        patientService.addPatient(parent());
        em.flush();

        when(securityHelper.getCurrentHospitalId()).thenReturn(MINE);
        Patient mine = patientService.addPatient(child());
        em.flush();

        assertThat(mine.getId()).isNotNull();
        assertThat(patientRepository.countByHospitalId(MINE)).isEqualTo(1);
        assertThat(patientRepository.countByHospitalId(THEIRS)).isEqualTo(1);
    }

    // ── the phone update path ────────────────────────────────────────────────

    @Test
    void editingAPatientOntoAnExistingNumberConflicts() {
        Patient keeper = patientService.addPatient(parent());
        Patient mover = patientService.addPatient(
                patient("Meera Joshi", "9000000002", LocalDate.of(1995, 5, 5)));
        em.flush();

        Patient edit = patient("Meera Joshi", SHARED, LocalDate.of(1995, 5, 5));
        assertThatThrownBy(() -> patientService.updatePatient(mover.getId(), edit))
                .isInstanceOf(DuplicatePhoneConflictException.class);

        em.flush();
        em.clear();
        assertThat(patientRepository.findById(mover.getId()).orElseThrow().getPhone())
                .isEqualTo("9000000002");
        assertThat(patientRepository.findById(keeper.getId())).isPresent();
    }

    @Test
    void editingAPatientWithoutChangingItsPhoneIsNotAConflictWithItself() {
        Patient p = patientService.addPatient(parent());
        em.flush();

        Patient edit = patient("Rahul R Patil", SHARED, LocalDate.of(1987, 4, 2));
        Patient saved = patientService.updatePatient(p.getId(), edit);

        assertThat(saved.getName()).isEqualTo("Rahul R Patil");
        assertThat(saved.getPhone()).isEqualTo(SHARED);
    }

    @Test
    void anAcknowledgedEditOntoAnExistingNumberIsAllowedAndRecorded() {
        patientService.addPatient(parent());
        Patient mover = patientService.addPatient(
                patient("Meera Joshi", "9000000002", LocalDate.of(1995, 5, 5)));
        em.flush();

        Patient edit = patient("Meera Joshi", SHARED, LocalDate.of(1995, 5, 5));
        Patient saved = patientService.updatePatient(mover.getId(), edit, true);

        assertThat(saved.getPhone()).isEqualTo(SHARED);
        assertThat(saved.getDuplicatePhoneAckFor()).isEqualTo(SHARED);
    }

    // ── the value-bound acknowledgement ──────────────────────────────────────

    /**
     * The defect this design exists to avoid. If the acknowledgement were a boolean, a patient
     * acknowledged on X would stay exempt forever — including on a number nobody ever looked at.
     * It is the number itself, so moving off X ends the exemption.
     */
    @Test
    void anAcknowledgementForXDoesNotExemptY() {
        patientService.addPatient(parent());
        Patient acked = patientService.addPatient(child(), true);
        em.flush();
        assertThat(acked.getDuplicatePhoneAckFor()).isEqualTo(SHARED);

        // Somebody else already holds Y.
        patientService.addPatient(patient("Unrelated", "9000000009", LocalDate.of(1970, 7, 7)));
        em.flush();

        Patient moveToY = patient("Aarav Patil", "9000000009", LocalDate.of(2017, 9, 21));
        assertThatThrownBy(() -> patientService.updatePatient(acked.getId(), moveToY))
                .isInstanceOf(DuplicatePhoneConflictException.class);
    }

    @Test
    void movingOffTheAcknowledgedNumberClearsTheAcknowledgement() {
        patientService.addPatient(parent());
        Patient acked = patientService.addPatient(child(), true);
        em.flush();

        Patient moveToFree = patient("Aarav Patil", "9000000007", LocalDate.of(2017, 9, 21));
        Patient saved = patientService.updatePatient(acked.getId(), moveToFree);
        em.flush();
        em.clear();

        Patient reloaded = patientRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getPhone()).isEqualTo("9000000007");
        assertThat(reloaded.getDuplicatePhoneAckFor()).isNull();
        assertThat(reloaded.getDuplicatePhoneAckAt()).isNull();
        assertThat(reloaded.getDuplicatePhoneAckBy()).isNull();
    }

    @Test
    void aLiveAcknowledgementIsBoundToTheNumberItNames() {
        Patient p = new Patient();
        p.setDuplicatePhoneAckFor(SHARED);

        assertThat(finder.hasLiveAcknowledgementFor(p, SHARED)).isTrue();
        assertThat(finder.hasLiveAcknowledgementFor(p, "9000000009")).isFalse();
        assertThat(finder.hasLiveAcknowledgementFor(new Patient(), SHARED)).isFalse();
    }

    // ── the acknowledgement is server-controlled ─────────────────────────────

    /**
     * A caller that could set the acknowledgement fields itself would turn the shared-phone
     * workflow into an opt-out. Jackson refuses to bind them; this proves the service discards
     * them too, so neither lock depends on the other.
     */
    @Test
    void acknowledgementFieldsArrivingOnTheRequestAreDiscarded() {
        Patient forged = parent();
        forged.setDuplicatePhoneAckFor("9999999999");
        forged.setDuplicatePhoneAckBy("attacker@example.test");
        forged.setDuplicatePhoneAckAt(java.time.LocalDateTime.now());

        Patient saved = patientService.addPatient(forged);
        em.flush();
        em.clear();

        Patient reloaded = patientRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getDuplicatePhoneAckFor()).isNull();
        assertThat(reloaded.getDuplicatePhoneAckBy()).isNull();
        assertThat(reloaded.getDuplicatePhoneAckAt()).isNull();
    }

    /**
     * A forged acknowledgement must not buy the caller a registration either — the field is
     * cleared before the duplicate check reads anything.
     */
    @Test
    void aForgedAcknowledgementDoesNotBypassTheCheck() {
        patientService.addPatient(parent());
        em.flush();

        Patient forged = child();
        forged.setDuplicatePhoneAckFor(SHARED);

        assertThatThrownBy(() -> patientService.addPatient(forged))
                .isInstanceOf(DuplicatePhoneConflictException.class);
        assertThat(countMine()).isEqualTo(1);
    }

    // ── nothing else moved ───────────────────────────────────────────────────

    @Test
    void registrationNumbersAreStillPatPlusId() {
        Patient first = patientService.addPatient(parent());
        Patient second = patientService.addPatient(child(), true);
        em.flush();

        assertThat(first.getCustomId()).isEqualTo("PAT" + first.getId());
        assertThat(second.getCustomId()).isEqualTo("PAT" + second.getId());
    }

    @Test
    void anEditLeavesTheRegistrationNumberAlone() {
        Patient p = patientService.addPatient(parent());
        em.flush();
        String before = p.getCustomId();

        Patient edit = patient("Rahul Patil", "9000000005", LocalDate.of(1987, 4, 2));
        Patient saved = patientService.updatePatient(p.getId(), edit);

        assertThat(saved.getCustomId()).isEqualTo(before);
    }
}

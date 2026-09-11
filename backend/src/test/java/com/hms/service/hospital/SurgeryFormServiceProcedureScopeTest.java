package com.hms.service.hospital;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hms.dto.SaveSurgeryFormRequest;
import com.hms.dto.SurgeryFormView;
import com.hms.entity.Surgery;
import com.hms.entity.SurgeryForm;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.SurgeryFormRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.security.NurseAccessGuard;
import com.hms.security.NurseWriteAccess;
import com.hms.security.PerformingNurseResolver;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OT Phase 1. The forms store used to be unique on (ipd_admission_id, form_type), so a
 * second procedure in one admission overwrote the first procedure's signed consent.
 * These tests pin the surgery-scoped, immutable-once-signed behaviour.
 */
@ExtendWith(MockitoExtension.class)
class SurgeryFormServiceProcedureScopeTest {

    private static final Long HOSPITAL = 7L;

    @Mock SurgeryFormRepository formRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock SurgeryRepository surgeryRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock NurseAccessGuard nurseAccessGuard;
    @Mock NurseWriteAccess nurseWriteAccess;
    @Mock PerformingNurseResolver performingNurseResolver;
    @org.mockito.Spy ObjectMapper objectMapper = new ObjectMapper();

    @Mock com.hms.service.RealtimeNotifier notifier;

    @InjectMocks SurgeryFormService service;

    /** In-memory stand-in for the table, keyed the way the new unique key is. */
    private final Map<String, SurgeryForm> table = new HashMap<>();
    private long nextId = 100L;

    @BeforeEach
    void setUp() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        lenient().when(securityHelper.getCurrentUserId()).thenReturn(42L);
        lenient().when(performingNurseResolver.resolve(any())).thenReturn(null);

        lenient().when(formRepository.save(any(SurgeryForm.class))).thenAnswer(inv -> {
            SurgeryForm f = inv.getArgument(0);
            if (f.getId() == null) f.setId(nextId++);
            if (f.getCreatedAt() == null) f.setCreatedAt(LocalDateTime.now());
            table.put(key(f), f);
            return f;
        });
        lenient().when(formRepository.findBySurgeryIdAndFormTypeAndIsCurrentTrue(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(0) + "|" + inv.getArgument(1) + "|current")));
    }

    private String key(SurgeryForm f) {
        return f.getSurgeryId() + "|" + f.getFormType() + "|" + (Boolean.TRUE.equals(f.getIsCurrent()) ? "current" : "v" + f.getVersion());
    }

    private Surgery surgery(long id, Long admissionId) {
        Surgery s = new Surgery();
        s.setId(id);
        s.setHospitalId(HOSPITAL);
        s.setIpdAdmissionId(admissionId);
        s.setPatientId(500L);
        s.setEncounterType(admissionId != null ? Surgery.ENCOUNTER_IPD : Surgery.ENCOUNTER_DAY_CARE);
        lenient().when(surgeryRepository.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private SaveSurgeryFormRequest req(Long surgeryId, String type, String value, Boolean sign) {
        SaveSurgeryFormRequest r = new SaveSurgeryFormRequest();
        r.setSurgeryId(surgeryId);
        r.setFormType(type);
        r.setData(Map.of("consent", value));
        r.setSign(sign);
        return r;
    }

    /**
     * The headline regression: two procedures in ONE admission, a consent signed on each.
     * Under the old (admission, formType) key the second overwrote the first.
     */
    @Test
    void twoSurgeriesInOneAdmission_eachKeepItsOwnSignedConsent() {
        surgery(1L, 900L);
        surgery(2L, 900L); // same admission, second procedure

        SurgeryFormView first = service.save(req(1L, "INFORMED_CONSENT_SURGERY", "first-procedure", true));
        SurgeryFormView second = service.save(req(2L, "INFORMED_CONSENT_SURGERY", "second-procedure", true));

        assertThat(first.getSurgeryId()).isEqualTo(1L);
        assertThat(second.getSurgeryId()).isEqualTo(2L);
        assertThat(first.getData()).containsEntry("consent", "first-procedure");
        assertThat(second.getData()).containsEntry("consent", "second-procedure");
        assertThat(table).hasSize(2); // both survive
    }

    @Test
    void savingOverAnUnsignedForm_updatesInPlace_keepingVersionOne() {
        surgery(1L, 900L);
        service.save(req(1L, "PRE_OP_CHECKLIST", "draft", null));
        SurgeryFormView updated = service.save(req(1L, "PRE_OP_CHECKLIST", "revised", null));

        assertThat(updated.getVersion()).isEqualTo(1);
        assertThat(updated.getData()).containsEntry("consent", "revised");
        assertThat(table).hasSize(1);
    }

    /** A signed consent is never mutated; an edit supersedes it and appends a version. */
    @Test
    void savingOverASignedForm_supersedesItAndAppendsANewVersion() {
        surgery(1L, 900L);
        SurgeryFormView v1 = service.save(req(1L, "GA_CONSENT", "original", true));
        assertThat(v1.getSignedAt()).isNotNull();

        SurgeryFormView v2 = service.save(req(1L, "GA_CONSENT", "amended", null));

        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.getSignedAt()).isNull();
        // The superseded row is retained, no longer current, still carrying its signature.
        SurgeryForm superseded = table.get("1|GA_CONSENT|v1");
        assertThat(superseded).isNotNull();
        assertThat(superseded.getIsCurrent()).isNull();
        assertThat(superseded.getSignedAt()).isNotNull();
        assertThat(superseded.getDataJson()).contains("original");
    }

    @Test
    void signingTwice_isRejected() {
        surgery(1L, 900L);
        service.save(req(1L, "BLOOD_CONSENT", "x", true));

        assertThatThrownBy(() -> service.sign(1L, "BLOOD_CONSENT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already signed");
    }

    @Test
    void signingAnUnsavedForm_isRejected() {
        surgery(1L, 900L);
        assertThatThrownBy(() -> service.sign(1L, "BLOOD_CONSENT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Save the form before signing");
    }

    /** Day-care: no admission, so the ward-keyed nurse guard cannot and must not run. */
    @Test
    void dayCareSurgery_savesWithoutAnAdmission_andSkipsTheWardGuard() {
        surgery(3L, null);

        SurgeryFormView v = service.save(req(3L, "PRE_ANAES_EVAL", "day-care", null));

        assertThat(v.getSurgeryId()).isEqualTo(3L);
        verify(nurseWriteAccess, never()).assertCanWriteFor(any());
    }

    @Test
    void inpatientSurgery_stillEnforcesTheNurseWriteGuard() {
        surgery(1L, 900L);
        service.save(req(1L, "IO_CHART", "x", null));
        verify(nurseWriteAccess).assertCanWriteFor(900L);
    }

    @Test
    void resolvingFromAnAdmissionWithNoSurgery_failsLoudly() {
        when(ipdAdmissionRepository.findById(900L)).thenReturn(Optional.of(admission()));
        when(surgeryRepository.findByIpdAdmissionIdAndStatusIn(any(), any())).thenReturn(List.of());
        when(surgeryRepository.findByIpdAdmissionIdOrderByRequestedAtDesc(900L)).thenReturn(List.of());

        SaveSurgeryFormRequest r = req(null, "BLOOD_CONSENT", "x", null);
        r.setIpdAdmissionId(900L);

        assertThatThrownBy(() -> service.save(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no surgery");
    }

    private com.hms.entity.IpdAdmission admission() {
        com.hms.entity.IpdAdmission a = new com.hms.entity.IpdAdmission();
        a.setId(900L);
        a.setHospitalId(HOSPITAL);
        a.setPatientId(500L);
        return a;
    }
}

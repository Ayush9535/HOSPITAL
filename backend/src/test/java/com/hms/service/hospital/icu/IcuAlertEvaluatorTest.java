package com.hms.service.hospital.icu;

import com.hms.entity.IcuAlertThreshold;
import com.hms.entity.IpdAdmission;
import com.hms.entity.NurseProfile;
import com.hms.entity.Patient;
import com.hms.entity.PatientNurseAssignment;
import com.hms.entity.VitalsRecord;
import com.hms.entity.Ward;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.WardRepository;
import com.hms.service.hospital.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ICU Phase 9 - threshold evaluation.
 *
 * <p>Everything this class does is compare a recorded number against a number an administrator
 * typed. These tests pin the boundary as hard as the behaviour: no grading, no default, nothing
 * fired for a value nobody configured, and nothing at all outside an ICU stay.
 *
 * <p><b>No de-duplication test exists because there is no de-duplication</b> (D-4). Repeated
 * breaching observations deliberately send repeated notifications.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IcuAlertEvaluatorTest {

    private static final Long HOSPITAL = 7L;
    private static final Long ASSIGNED_NURSE_USER = 21L;
    private static final Long INCHARGE_USER = 22L;

    @Mock IcuAlertThresholdService thresholdService;
    @Mock NotificationService notificationService;
    @Mock PatientNurseAssignmentRepository assignmentRepository;
    @Mock WardRepository wardRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock PatientRepository patientRepository;

    @InjectMocks IcuAlertEvaluator evaluator;

    private IpdAdmission admission;

    @BeforeEach
    void setUp() {
        admission = new IpdAdmission();
        admission.setId(11L);
        admission.setHospitalId(HOSPITAL);
        admission.setPatientId(5L);
        admission.setWardId(3L);
        admission.setIpdNumber("IPD-11");

        Patient p = new Patient();
        p.setName("Asha");
        when(patientRepository.findById(5L)).thenReturn(Optional.of(p));

        PatientNurseAssignment a = new PatientNurseAssignment();
        a.setNurseUserId(ASSIGNED_NURSE_USER);
        when(assignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(11L)).thenReturn(Optional.of(a));

        Ward w = new Ward();
        w.setWardId(3L);
        w.setInchargeNurseId(4L);
        when(wardRepository.findById(3L)).thenReturn(Optional.of(w));

        NurseProfile incharge = new NurseProfile();
        incharge.setId(4L);
        incharge.setHospitalId(HOSPITAL);
        incharge.setUserId(INCHARGE_USER);
        when(nurseProfileRepository.findById(4L)).thenReturn(Optional.of(incharge));
    }

    private IcuAlertThreshold threshold(String metricKey, String min, String max) {
        IcuAlertThreshold t = new IcuAlertThreshold();
        t.setHospitalId(HOSPITAL);
        t.setSource(AlertMetricRegistry.SOURCE_VITALS);
        t.setMetricKey(metricKey);
        t.setMinValue(min == null ? null : new BigDecimal(min));
        t.setMaxValue(max == null ? null : new BigDecimal(max));
        t.setEnabled(true);
        return t;
    }

    private void configured(IcuAlertThreshold... rows) {
        when(thresholdService.activeFor(HOSPITAL, AlertMetricRegistry.SOURCE_VITALS))
                .thenReturn(List.of(rows));
    }

    private VitalsRecord vitals(Integer map, Integer pulse) {
        VitalsRecord v = new VitalsRecord();
        v.setHospitalId(HOSPITAL);
        v.setIpdAdmissionId(11L);
        v.setMapMmhg(map);
        v.setPulse(pulse);
        return v;
    }

    // ── comparison ───────────────────────────────────────────────────────────

    @Test
    void aValueBelowTheMinimumNotifies() {
        configured(threshold("map_mmhg", "65", null));

        evaluator.evaluateVitals(vitals(58, null), admission, () -> true);

        verify(notificationService).create(eq(ASSIGNED_NURSE_USER), eq(HOSPITAL), eq("ICU_ALERT"),
                contains("below 65"), contains("58"), eq("IPD_ADMISSION"), eq(11L));
        verify(notificationService).create(eq(INCHARGE_USER), eq(HOSPITAL), anyString(),
                anyString(), anyString(), anyString(), anyLong());
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void aValueAboveTheMaximumNotifies() {
        configured(threshold("pulse", null, "130"));

        evaluator.evaluateVitals(vitals(null, 145), admission, () -> true);

        verify(notificationService, times(2)).create(anyLong(), eq(HOSPITAL), eq("ICU_ALERT"),
                contains("above 130"), contains("145"), anyString(), anyLong());
    }

    @Test
    void aValueInsideTheRangeNotifiesNobody() {
        configured(threshold("map_mmhg", "65", "110"));

        evaluator.evaluateVitals(vitals(80, null), admission, () -> true);

        verifyNoInteractions(notificationService);
    }

    @Test
    void theBoundsThemselvesDoNotFire() {
        // "below 65" means below, not at. An off-by-one here would alert on every normal chart.
        configured(threshold("map_mmhg", "65", "110"));

        evaluator.evaluateVitals(vitals(65, null), admission, () -> true);
        evaluator.evaluateVitals(vitals(110, null), admission, () -> true);

        verifyNoInteractions(notificationService);
    }

    @Test
    void onlyTheBreachingMetricFires() {
        configured(threshold("map_mmhg", "65", null), threshold("pulse", null, "130"));

        evaluator.evaluateVitals(vitals(58, 90), admission, () -> true);

        verify(notificationService, times(2)).create(anyLong(), anyLong(), anyString(),
                contains("MAP"), anyString(), anyString(), anyLong());
        verify(notificationService, never()).create(anyLong(), anyLong(), anyString(),
                contains("Pulse"), anyString(), anyString(), anyLong());
    }

    // ── nothing fires without an explicit configuration ──────────────────────

    @Test
    void anUnconfiguredMetricNeverFires() {
        // No default threshold exists anywhere: a value with no row is simply a value.
        configured();

        evaluator.evaluateVitals(vitals(20, 200), admission, () -> true);

        verifyNoInteractions(notificationService);
    }

    @Test
    void aValueThatWasNotMeasuredNeverFires() {
        configured(threshold("map_mmhg", "65", null));

        evaluator.evaluateVitals(vitals(null, 90), admission, () -> true);

        verifyNoInteractions(notificationService);
    }

    // ── ICU scope ────────────────────────────────────────────────────────────

    @Test
    void aWardObservationOutsideAnIcuStayNeverFires() {
        // D-1: ICU vitals only. A ward patient's low MAP is not an ICU alert.
        configured(threshold("map_mmhg", "65", null));

        evaluator.evaluateVitals(vitals(40, null), admission, () -> false);

        verifyNoInteractions(notificationService);
    }

    // ── recipients ───────────────────────────────────────────────────────────

    @Test
    void withNoAssignedNurseTheInchargeStillHears() {
        configured(threshold("map_mmhg", "65", null));
        when(assignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(11L))
                .thenReturn(Optional.empty());

        evaluator.evaluateVitals(vitals(58, null), admission, () -> true);

        verify(notificationService).create(eq(INCHARGE_USER), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyLong());
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void anInchargeProfileFromAnotherHospitalIsNotNotified() {
        configured(threshold("map_mmhg", "65", null));
        NurseProfile foreign = new NurseProfile();
        foreign.setId(4L);
        foreign.setHospitalId(99L);
        foreign.setUserId(INCHARGE_USER);
        when(nurseProfileRepository.findById(4L)).thenReturn(Optional.of(foreign));

        evaluator.evaluateVitals(vitals(58, null), admission, () -> true);

        verify(notificationService, never()).create(eq(INCHARGE_USER), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyLong());
        verify(notificationService).create(eq(ASSIGNED_NURSE_USER), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void withNobodyToTellNothingIsSent() {
        configured(threshold("map_mmhg", "65", null));
        when(assignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(11L))
                .thenReturn(Optional.empty());
        when(wardRepository.findById(3L)).thenReturn(Optional.empty());

        evaluator.evaluateVitals(vitals(58, null), admission, () -> true);

        verifyNoInteractions(notificationService);
    }

    // ── fail-safe ────────────────────────────────────────────────────────────

    @Test
    void aNotificationFailureNeverEscapesTheEvaluator() {
        // The clinical write is the point of the transaction; an alert must never cost it.
        configured(threshold("map_mmhg", "65", null));
        doThrow(new RuntimeException("mail room on fire"))
                .when(notificationService).create(anyLong(), anyLong(), anyString(), anyString(),
                        anyString(), anyString(), anyLong());

        evaluator.evaluateVitals(vitals(58, null), admission, () -> true);
        // no exception
    }

    @Test
    void aThresholdLookupFailureNeverEscapesTheEvaluator() {
        when(thresholdService.activeFor(anyLong(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        evaluator.evaluateVitals(vitals(58, null), admission, () -> true);
        // no exception
    }

    @Test
    void aNullRecordOrAdmissionIsIgnored() {
        evaluator.evaluateVitals(null, admission, () -> true);
        evaluator.evaluateVitals(vitals(58, null), null, () -> true);

        verifyNoInteractions(notificationService);
    }

    // ── the D-4 limitation, stated as a test so it is not mistaken for a bug ──

    @Test
    void repeatedBreachesSendRepeatedNotifications() {
        // No alert-event table means no de-duplication (D-4). Charting a breaching value three
        // times sends three notifications per recipient. Documented, not accidental.
        configured(threshold("map_mmhg", "65", null));

        evaluator.evaluateVitals(vitals(58, null), admission, () -> true);
        evaluator.evaluateVitals(vitals(58, null), admission, () -> true);
        evaluator.evaluateVitals(vitals(58, null), admission, () -> true);

        verify(notificationService, times(6)).create(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void nothingBeyondAComparisonIsProduced() {
        // No severity, priority or colour reaches the notification: the type is flat and the
        // message is the value and the bound, nothing more.
        configured(threshold("map_mmhg", "65", null));

        evaluator.evaluateVitals(vitals(58, null), admission, () -> true);

        verify(notificationService, times(2)).create(anyLong(), anyLong(), eq("ICU_ALERT"),
                any(), any(), eq("IPD_ADMISSION"), eq(11L));
    }
}

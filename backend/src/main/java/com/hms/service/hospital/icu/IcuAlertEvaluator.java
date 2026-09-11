package com.hms.service.hospital.icu;

import com.hms.entity.IcuAlertThreshold;
import com.hms.entity.IpdAdmission;
import com.hms.entity.NurseProfile;
import com.hms.entity.Patient;
import com.hms.entity.VitalsRecord;
import com.hms.entity.Ward;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.WardRepository;
import com.hms.service.hospital.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * IcuAlertEvaluator - compares a saved ICU vitals observation against this hospital's configured
 * thresholds and notifies (ICU Phase 9).
 *
 * <p><b>This compares two numbers.</b> It does not grade, rank, prioritise, colour, escalate or
 * decide anything. A breach is a breach, and the only thing that makes one is a number a hospital
 * administrator typed into the settings screen.
 *
 * <p><b>Scope is ICU-4 vitals only (D-1)</b>, and only observations that fall inside an ICU stay:
 * a ward patient's pulse is not an ICU alert. Nothing here reads I/O, infusions, ventilator
 * settings, severity scores or labs.
 *
 * <p><b>Fail-safe.</b> Every path is wrapped: an alert must never cost a vitals row. That is also
 * why the whole method swallows rather than rethrows — {@code NotificationService.create} is
 * already fail-safe, and this adds the same guarantee around the lookup and comparison.
 *
 * <p><b>Known limitation, by decision (D-4).</b> There is no alert-event table, so there is no
 * de-duplication: charting a breaching value five times sends five notifications, and there is no
 * record of what fired. That is the roadmap's "only the threshold storage is new" scope held
 * exactly, not an oversight.
 */
@Service
public class IcuAlertEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(IcuAlertEvaluator.class);

    /** Reuses the existing per-user notification pipeline; no new delivery mechanism (D-2). */
    private static final String NOTIFICATION_TYPE = "ICU_ALERT";
    private static final String REFERENCE_TYPE = "IPD_ADMISSION";

    @Autowired private IcuAlertThresholdService thresholdService;
    @Autowired private NotificationService notificationService;
    @Autowired private PatientNurseAssignmentRepository assignmentRepository;
    @Autowired private WardRepository wardRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private PatientRepository patientRepository;

    /**
     * Evaluates one saved ICU vitals record.
     *
     * @param inIcu asked ONLY once a threshold is known to be configured — a hospital with no
     *              thresholds must not pay for an ICU-stay lookup on every ward vitals save
     */
    public void evaluateVitals(VitalsRecord saved, IpdAdmission admission,
                               java.util.function.BooleanSupplier inIcu) {
        try {
            if (saved == null || admission == null) return;
            List<IcuAlertThreshold> thresholds = thresholdService.activeFor(
                    saved.getHospitalId(), AlertMetricRegistry.SOURCE_VITALS);
            if (thresholds.isEmpty()) return; // no row means no alert
            // D-1: ICU vitals only. A ward patient's low MAP is not an ICU alert.
            if (!inIcu.getAsBoolean()) return;

            Set<Long> recipients = recipientsFor(admission);
            if (recipients.isEmpty()) return;

            String patientName = patientRepository.findById(admission.getPatientId())
                    .map(Patient::getName).orElse("Patient");

            for (IcuAlertThreshold t : thresholds) {
                AlertMetricRegistry.find(t.getSource(), t.getMetricKey()).ifPresent(metric -> {
                    Integer value = metric.reader().apply(saved);
                    if (value == null) return; // not measured on this observation
                    String breach = breachOf(value, t);
                    if (breach == null) return;
                    notify(recipients, admission, patientName, metric, value, breach);
                });
            }
        } catch (Exception e) {
            // An alert must never cost a clinical record.
            logger.warn("ICU alert evaluation failed for admission {}: {}",
                    admission == null ? null : admission.getId(), e.getMessage());
        }
    }

    /** "below 65" / "above 120", or null when the value is inside the configured range. */
    private String breachOf(Integer value, IcuAlertThreshold t) {
        BigDecimal v = BigDecimal.valueOf(value);
        if (t.getMinValue() != null && v.compareTo(t.getMinValue()) < 0) {
            return "below " + t.getMinValue().stripTrailingZeros().toPlainString();
        }
        if (t.getMaxValue() != null && v.compareTo(t.getMaxValue()) > 0) {
            return "above " + t.getMaxValue().stripTrailingZeros().toPlainString();
        }
        return null;
    }

    /**
     * The assigned staff nurse and the ward incharge (D-2).
     *
     * <p>Both already have access to this patient's chart, so a notification tells them nothing
     * they could not already open. That is the whole reason these two and not, say, every nurse
     * on the ward.
     */
    private Set<Long> recipientsFor(IpdAdmission admission) {
        Set<Long> out = new LinkedHashSet<>();
        assignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(admission.getId())
                .ifPresent(a -> {
                    if (a.getNurseUserId() != null) out.add(a.getNurseUserId());
                });
        if (admission.getWardId() != null) {
            wardRepository.findById(admission.getWardId())
                    .map(Ward::getInchargeNurseId)
                    .flatMap(nurseProfileRepository::findById)
                    // Tenant check: a ward's incharge profile must belong to the same hospital.
                    .filter(p -> admission.getHospitalId().equals(p.getHospitalId()))
                    .map(NurseProfile::getUserId)
                    .ifPresent(out::add);
        }
        return out;
    }

    private void notify(Set<Long> recipients, IpdAdmission admission, String patientName,
                        AlertMetricRegistry.Metric metric, Integer value, String breach) {
        String unit = metric.unit() == null ? "" : " " + metric.unit();
        String title = metric.label() + " " + breach;
        String message = patientName + " (" + admission.getIpdNumber() + "): "
                + metric.label() + " " + value + unit + " is " + breach + ".";
        for (Long userId : recipients) {
            // Already fail-safe and already broadcasts its own REFRESH_DATA.
            notificationService.create(userId, admission.getHospitalId(), NOTIFICATION_TYPE,
                    title, message, REFERENCE_TYPE, admission.getId());
        }
    }
}

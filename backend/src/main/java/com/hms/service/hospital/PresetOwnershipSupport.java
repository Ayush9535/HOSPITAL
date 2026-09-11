package com.hms.service.hospital;

import com.hms.entity.Doctor;
import com.hms.repository.DoctorRepository;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Shared ownership/visibility resolution and real-time broadcast for the preset
 * services (prescription presets and consultation-note presets). Both services
 * had identical copies of this logic; centralising it here removes that
 * duplication without changing behaviour.
 */
@Component
public class PresetOwnershipSupport {

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private HospitalWebSocketHandler webSocketHandler;

    public boolean isAdmin() {
        return "HOSPITAL_ADMIN".equals(securityHelper.getCurrentUserRole());
    }

    /**
     * The id of the doctor making the request, or null if the caller is not a
     * doctor (e.g. an admin) or has no matching doctor profile.
     */
    public Long currentDoctorIdOrNull() {
        if (!"DOCTOR".equals(securityHelper.getCurrentUserRole())) {
            return null;
        }
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return doctorRepository.findByEmailAndHospitalId(securityHelper.getCurrentUserEmail(), hospitalId)
                .map(Doctor::getId)
                .orElse(null);
    }

    /**
     * Decide the owning doctor for a preset being created/edited.
     * - An admin explicitly assigning to a doctor via the dashboard wins.
     * - Otherwise, if the caller acts as a doctor — a real DOCTOR, or a
     *   single-doctor-clinic admin who consults as the sole doctor (resolved by
     *   email) — the preset is owned by that doctor.
     * - A pure admin with no doctor profile falls back to the dashboard choice
     *   (null = shared).
     */
    public Long resolveOwnerDoctorId(Long requestedDoctorId, Long hospitalId) {
        if (isAdmin() && requestedDoctorId != null) {
            return sanitizeAssignedDoctorId(requestedDoctorId, hospitalId);
        }
        Long selfDoctorId = doctorRepository.findByEmailAndHospitalId(securityHelper.getCurrentUserEmail(), hospitalId)
                .map(Doctor::getId)
                .orElse(null);
        if (selfDoctorId != null) {
            return selfDoctorId;
        }
        return sanitizeAssignedDoctorId(requestedDoctorId, hospitalId);
    }

    /**
     * Validate that an admin-supplied doctorId (may be null = shared) belongs to
     * this hospital; returns the id unchanged, or null when shared.
     */
    public Long sanitizeAssignedDoctorId(Long doctorId, Long hospitalId) {
        if (doctorId == null) {
            return null;
        }
        boolean belongs = doctorRepository.findById(doctorId)
                .map(d -> hospitalId.equals(d.getHospitalId()))
                .orElse(false);
        if (!belongs) {
            throw new IllegalArgumentException("Assigned doctor does not belong to this hospital");
        }
        return doctorId;
    }

    /** Tell every connected client for this hospital to reload its preset lists. */
    public void notifyPresetsChanged(Long hospitalId) {
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"PRESETS_UPDATED\"}");
        } catch (Exception ignored) {
            // best-effort real-time sync; a failed broadcast must not fail the write
        }
    }
}

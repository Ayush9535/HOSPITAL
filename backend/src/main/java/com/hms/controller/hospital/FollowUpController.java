package com.hms.controller.hospital;

import com.hms.dto.ApiResponse;
import com.hms.dto.FollowUpDTO;
import com.hms.repository.DoctorRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.hospital.FollowUpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The outstanding-follow-up list.
 *
 * <p>Read-only by design, and that is the whole point of this checkpoint: the previous way to see
 * due follow-ups was to open the OPD queue, which created the encounters as a side effect. A GET
 * here creates nothing. Turning a due follow-up into an actual visit is a separate, explicit
 * action taken when the patient is standing at the desk.
 *
 * <p>No pharmacy alias: a pharmacy has no consultations to follow up.
 */
@RestController
@RequestMapping({"/hospital/follow-ups", "/clinic/follow-ups"})
public class FollowUpController {

    @Autowired private FollowUpService followUpService;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private DoctorRepository doctorRepository;

    /**
     * Outstanding follow-ups for this facility.
     *
     * @param timing      optional bucket: OVERDUE, DUE_TODAY or UPCOMING. All three when omitted.
     * @param mine        a doctor asking only for their own patients
     * @param overdueDays how far back to look; the service's default window when omitted
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST')")
    public ResponseEntity<?> outstanding(
            @RequestParam(required = false) String timing,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @RequestParam(required = false) Integer overdueDays) {

        Long doctorId = null;
        if (mine) {
            Long hospitalId = securityHelper.getCurrentHospitalId();
            // Resolved from the caller's own identity, never from a request parameter: a doctor
            // id in the query string would let anyone read any doctor's list.
            doctorId = doctorRepository
                    .findByEmailAndHospitalId(securityHelper.getCurrentUserEmail(), hospitalId)
                    .map(com.hms.entity.Doctor::getId)
                    .orElse(null);
            if (doctorId == null) {
                return ResponseEntity.ok(List.of());
            }
        }

        try {
            List<FollowUpDTO> rows = followUpService.outstanding(timing, doctorId, overdueDays);
            return ResponseEntity.ok(rows);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * The patient turned up. Creates the follow-up visit and closes the follow-up.
     *
     * <p>Same authority as creating any other OPD — this is that action, reached from the due
     * list instead of the registration form, so it would be odd for the two to differ.
     *
     * <p>The body carries only the presenting complaint. Everything else — facility, patient,
     * doctor, the original consultation — is read from the tenant-scoped record, because a
     * client that could supply them could action one facility's follow-up into another's queue.
     */
    @PostMapping("/{medicalRecordId}/arrive")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST')")
    public ResponseEntity<?> recordArrival(@PathVariable Long medicalRecordId,
                                           @RequestBody(required = false) ArrivalRequest body) {
        return ResponseEntity.ok(
                followUpService.recordArrival(medicalRecordId, body == null ? null : body.getProblem()));
    }

    /**
     * Moves an outstanding follow-up. It stays open; only its date changes.
     *
     * <p>Reception may do this. Rescheduling is the same administrative act as moving any other
     * appointment, and the clinical instruction itself is unchanged.
     */
    @PostMapping("/{medicalRecordId}/reschedule")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST')")
    public ResponseEntity<?> reschedule(@PathVariable Long medicalRecordId,
                                        @RequestBody RescheduleRequest body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("A new follow-up date is required."));
        }
        followUpService.reschedule(medicalRecordId, body.getNewFollowUpDate(),
                body.getInstructions(), body.getReason());
        return ResponseEntity.ok(java.util.Map.of("message", "Follow-up rescheduled"));
    }

    /**
     * Closes a follow-up without a visit.
     *
     * <p>Doctor or admin only, and deliberately narrower than reschedule: deciding a patient no
     * longer needs to be seen is a clinical judgement, not a desk one. Reception cancels with a
     * reason instead, which says the appointment was called off rather than that the question
     * was resolved.
     */
    @PostMapping("/{medicalRecordId}/complete")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR')")
    public ResponseEntity<?> complete(@PathVariable Long medicalRecordId,
                                      @RequestBody(required = false) ReasonRequest body) {
        followUpService.complete(medicalRecordId, body == null ? null : body.getReason());
        return ResponseEntity.ok(java.util.Map.of("message", "Follow-up completed"));
    }

    /** Calls off a follow-up. The reason is required and recorded. */
    @PostMapping("/{medicalRecordId}/cancel")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST')")
    public ResponseEntity<?> cancel(@PathVariable Long medicalRecordId,
                                    @RequestBody(required = false) ReasonRequest body) {
        followUpService.cancel(medicalRecordId, body == null ? null : body.getReason());
        return ResponseEntity.ok(java.util.Map.of("message", "Follow-up cancelled"));
    }

    public static class RescheduleRequest {
        private java.time.LocalDate newFollowUpDate;
        private String instructions;
        private String reason;
        public java.time.LocalDate getNewFollowUpDate() { return newFollowUpDate; }
        public void setNewFollowUpDate(java.time.LocalDate d) { this.newFollowUpDate = d; }
        public String getInstructions() { return instructions; }
        public void setInstructions(String instructions) { this.instructions = instructions; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class ReasonRequest {
        private String reason;
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /** Deliberately just the one field; see recordArrival. */
    public static class ArrivalRequest {
        private String problem;
        public String getProblem() { return problem; }
        public void setProblem(String problem) { this.problem = problem; }
    }
}

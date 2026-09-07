package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.CreateSurgeryRequest;
import com.hms.dto.ScheduleSurgeryRequest;
import com.hms.dto.RecordAnaesthesiaClearanceRequest;
import com.hms.dto.RecordEmergencyOverrideRequest;
import com.hms.entity.HospitalType;
import com.hms.security.RequireModule;
import com.hms.security.TenantType;
import com.hms.service.hospital.SurgeryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SurgeryController - Operation Theatre workflow (OT module, Phase 2).
 * HOSPITAL tenant only, OT-gated. Doctors create requests; reception
 * schedules/starts/completes/cancels; surgeons read their own board.
 */
@RestController
@RequestMapping("/hospital/surgeries")
@TenantType(HospitalType.HOSPITAL)
@RequireModule("OT")
public class SurgeryController {

    @Autowired
    private SurgeryService service;
    @Autowired
    private com.hms.service.hospital.ot.PreOpSafetyService preOpSafetyService;

    @PostMapping
    @PreAuthorize("hasAuthority('OT_CREATE')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateSurgeryRequest req) {
        return ResponseEntity.ok(service.createRequest(req));
    }

    @GetMapping("/admission/{admissionId}/active")
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> activeForAdmission(@PathVariable Long admissionId) {
        return ResponseEntity.ok(service.getActiveForAdmission(admissionId));
    }

    @GetMapping("/requests")
    @PreAuthorize("hasAuthority('OT_SCHEDULE')")
    public ResponseEntity<?> requests() {
        return ResponseEntity.ok(service.listRequests());
    }

    @GetMapping("/board")
    @PreAuthorize("hasAuthority('OT_SCHEDULE')")
    public ResponseEntity<?> board() {
        return ResponseEntity.ok(service.listBoard());
    }

    @GetMapping("/my-board")
    @PreAuthorize("hasAuthority('OT_CREATE')")
    public ResponseEntity<?> myBoard() {
        return ResponseEntity.ok(service.listMyBoard());
    }

    @GetMapping("/surgeons")
    @PreAuthorize("hasAuthority('OT_SCHEDULE')")
    public ResponseEntity<?> surgeons() {
        return ResponseEntity.ok(service.listSurgeons());
    }

    /** Only needed when APPROVAL_MODE is SINGLE or DUAL; NONE auto-approves at scheduling. */
    @PostMapping("/{publicId}/approve")
    @PreAuthorize("hasAuthority('OT_APPROVE')")
    public ResponseEntity<?> approve(@PathVariable String publicId) {
        return ResponseEntity.ok(service.approve(publicId));
    }

    @PostMapping("/{publicId}/schedule")
    @PreAuthorize("hasAuthority('OT_SCHEDULE')")
    public ResponseEntity<?> schedule(@PathVariable String publicId, @Valid @RequestBody ScheduleSurgeryRequest req) {
        return ResponseEntity.ok(service.schedule(publicId, req));
    }

    @PostMapping("/{publicId}/pre-op")
    @PreAuthorize("hasAuthority('OT_PRE_OP')")
    public ResponseEntity<?> enterPreOp(@PathVariable String publicId) {
        return ResponseEntity.ok(preOpSafetyService.enterPreOp(publicId));
    }

    @PostMapping("/{publicId}/anaesthesia-clearance")
    @PreAuthorize("hasAuthority('OT_ANAESTHESIA_CLEARANCE')")
    public ResponseEntity<?> recordAnaesthesiaClearance(@PathVariable String publicId,
            @RequestBody RecordAnaesthesiaClearanceRequest request) {
        return ResponseEntity.ok(preOpSafetyService.recordClearance(publicId, request));
    }

    @PostMapping("/{publicId}/emergency-override")
    @PreAuthorize("hasAuthority('OT_EMERGENCY_OVERRIDE')")
    public ResponseEntity<?> recordEmergencyOverride(@PathVariable String publicId,
            @RequestBody RecordEmergencyOverrideRequest request) {
        return ResponseEntity.ok(preOpSafetyService.recordEmergencyOverride(publicId, request));
    }

    @PostMapping("/{publicId}/start")
    @PreAuthorize("hasAuthority('OT_START')")
    public ResponseEntity<?> start(@PathVariable String publicId) {
        return ResponseEntity.ok(service.start(publicId));
    }

    @PostMapping("/{publicId}/complete")
    @PreAuthorize("hasAuthority('OT_COMPLETE')")
    public ResponseEntity<?> complete(@PathVariable String publicId) {
        return ResponseEntity.ok(service.complete(publicId));
    }

    /** Body is optional; an absent reason is recorded as OTHER rather than rejected. */
    @PostMapping("/{publicId}/cancel")
    @PreAuthorize("hasAuthority('OT_CANCEL')")
    public ResponseEntity<?> cancel(@PathVariable String publicId,
            @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        service.cancel(publicId, b.get("reasonCode"), b.get("reasonText"));
        return ResponseEntity.ok(Map.of("message", "Surgery cancelled"));
    }

    /** Close a completed case: documentation done and patient dispositioned. Never billing. */
    @PostMapping("/{publicId}/close")
    @PreAuthorize("hasAuthority('OT_CLOSE')")
    public ResponseEntity<?> close(@PathVariable String publicId) {
        return ResponseEntity.ok(service.close(publicId));
    }

    /** Postpone returns the case to the waiting list; cancel is terminal. */
    @PostMapping("/{publicId}/postpone")
    @PreAuthorize("hasAuthority('OT_RESCHEDULE')")
    public ResponseEntity<?> postpone(@PathVariable String publicId,
            @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        service.postpone(publicId, b.get("reasonCode"), b.get("reasonText"));
        return ResponseEntity.ok(Map.of("message", "Surgery postponed"));
    }

    /**
     * Today's OT List: patient, age, procedure, theatre, time, surgeon. The one artefact
     * every hospital produces each morning.
     */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> otList(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        return ResponseEntity.ok(service.listForDate(date));
    }

    /** The waiting list is derived: APPROVED cases with no slot. It is not a status. */
    @GetMapping("/waiting-list")
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> waitingList() {
        return ResponseEntity.ok(service.listWaitingList());
    }

    /** Who moved this case, when, and why. */
    @GetMapping("/{publicId}/timeline")
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> timeline(@PathVariable String publicId) {
        return ResponseEntity.ok(service.timeline(publicId));
    }

    @GetMapping("/cancellation-reasons")
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> cancellationReasons() {
        return ResponseEntity.ok(com.hms.service.hospital.ot.CancellationReasons.all());
    }
}

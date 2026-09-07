package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.NursingNoteRequest;
import com.hms.service.hospital.NursingNoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * NursingNoteController - nursing observation notes (Phase 1 Nurse module).
 * HOSPITAL-tenant only, NURSING-gated. Writes are nurse-only (assignment-gated
 * in the service); reads also open to doctors and admins.
 */
@RestController
@RequestMapping("/hospital/nurse/notes")
public class NursingNoteController {

    @Autowired
    private NursingNoteService noteService;

    @PostMapping
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> create(@Valid @RequestBody NursingNoteRequest req) {
        return ResponseEntity.ok(noteService.create(req));
    }

    @GetMapping("/admission/{admissionId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> getByAdmission(@PathVariable Long admissionId) {
        return ResponseEntity.ok(noteService.getByAdmission(admissionId));
    }

    @GetMapping("/surgery/{surgeryId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> getBySurgery(@PathVariable Long surgeryId) {
        return ResponseEntity.ok(noteService.getBySurgery(surgeryId));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> update(@PathVariable String publicId, @Valid @RequestBody NursingNoteRequest req) {
        return ResponseEntity.ok(noteService.update(publicId, req));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> delete(@PathVariable String publicId) {
        noteService.softDelete(publicId);
        return ResponseEntity.ok(Map.of("message", "Note deleted"));
    }
}

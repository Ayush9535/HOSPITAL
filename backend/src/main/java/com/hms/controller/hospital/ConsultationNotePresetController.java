package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.ConsultationNotePresetDTO;
import com.hms.entity.ConsultationNotePreset;
import com.hms.service.hospital.ConsultationNotePresetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/hospital/consultation-note-presets", "/clinic/consultation-note-presets", "/pharmacy/consultation-note-presets"})
public class ConsultationNotePresetController {

    @Autowired
    private ConsultationNotePresetService presetService;

    @Autowired
    private com.hms.repository.DoctorRepository doctorRepository;

    private String doctorNameOrNull(Long doctorId) {
        if (doctorId == null) return null;
        return doctorRepository.findById(doctorId).map(com.hms.entity.Doctor::getName).orElse(null);
    }

    private ConsultationNotePresetDTO toDto(ConsultationNotePreset p) {
        return new ConsultationNotePresetDTO(p.getId(), p.getFieldType(), p.getText(), p.getDisplayOrder(),
                p.getDoctorId(), doctorNameOrNull(p.getDoctorId()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> listPresets(@RequestParam String fieldType) {
        List<ConsultationNotePresetDTO> dtos = presetService.listPresets(fieldType).stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> createPreset(@Valid @RequestBody ConsultationNotePresetDTO dto) {
        try {
            ConsultationNotePreset saved = presetService.createPreset(dto.getFieldType(), dto.getText(), dto.getDoctorId());
            return ResponseEntity.ok(toDto(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> updatePreset(@PathVariable Long id, @RequestBody ConsultationNotePresetDTO dto) {
        try {
            ConsultationNotePreset saved = presetService.updatePreset(id, dto.getText(), dto.getDisplayOrder(), dto.getDoctorId());
            return ResponseEntity.ok(toDto(saved));
        } catch (RuntimeException e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> deletePreset(@PathVariable Long id) {
        try {
            presetService.deletePreset(id);
            return ResponseEntity.ok("Preset deleted successfully");
        } catch (RuntimeException e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }
}

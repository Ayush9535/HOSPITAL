package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.PrescriptionPresetDTO;
import com.hms.dto.PrescriptionPresetItemDTO;
import com.hms.entity.PrescriptionPreset;
import com.hms.entity.PrescriptionPresetItem;
import com.hms.service.hospital.PrescriptionPresetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/hospital/prescription-presets", "/clinic/prescription-presets", "/pharmacy/prescription-presets"})
public class PrescriptionPresetController {

    @Autowired
    private PrescriptionPresetService presetService;

    @Autowired
    private com.hms.repository.DoctorRepository doctorRepository;

    private PrescriptionPresetItemDTO toItemDto(PrescriptionPresetItem i) {
        return new PrescriptionPresetItemDTO(i.getId(), i.getMedicineName(), i.getDosage(), i.getFrequency(),
                i.getDuration(), i.getInstructions(), i.getMedicineId(), i.getQuantity());
    }

    private String doctorNameOrNull(Long doctorId) {
        if (doctorId == null) return null;
        return doctorRepository.findById(doctorId).map(com.hms.entity.Doctor::getName).orElse(null);
    }

    private PrescriptionPresetDTO toDto(PrescriptionPreset p) {
        List<PrescriptionPresetItemDTO> items = presetService.getItems(p.getId()).stream()
                .map(this::toItemDto)
                .toList();
        return new PrescriptionPresetDTO(p.getId(), p.getName(), items, p.getDisplayOrder(),
                p.getDoctorId(), doctorNameOrNull(p.getDoctorId()), p.getPresetType());
    }

    private PrescriptionPresetItem toItemEntity(PrescriptionPresetItemDTO dto) {
        PrescriptionPresetItem item = new PrescriptionPresetItem();
        item.setMedicineName(dto.getMedicineName());
        item.setDosage(dto.getDosage());
        item.setFrequency(dto.getFrequency());
        item.setDuration(dto.getDuration());
        item.setInstructions(dto.getInstructions());
        item.setMedicineId(dto.getMedicineId());
        item.setQuantity(dto.getQuantity());
        return item;
    }

    /** @param type PRESCRIPTION (default) or IN_CLINIC — two preset lists sharing one table. */
    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> listPresets(@RequestParam(required = false) String type) {
        List<PrescriptionPresetDTO> dtos = presetService.listPresets(type).stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> createPreset(@Valid @RequestBody PrescriptionPresetDTO dto) {
        try {
            List<PrescriptionPresetItem> items = dto.getItems() == null ? Collections.emptyList() : dto.getItems().stream()
                    .map(this::toItemEntity)
                    .collect(Collectors.toList());
            PrescriptionPreset saved = presetService.createPreset(dto.getName(), items, dto.getDoctorId(), dto.getPresetType());
            return ResponseEntity.ok(toDto(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> updatePreset(@PathVariable Long id, @Valid @RequestBody PrescriptionPresetDTO dto) {
        try {
            List<PrescriptionPresetItem> items = dto.getItems() == null ? null : dto.getItems().stream()
                    .map(this::toItemEntity)
                    .collect(Collectors.toList());
            PrescriptionPreset saved = presetService.updatePreset(id, dto.getName(), items, dto.getDisplayOrder(), dto.getDoctorId());
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

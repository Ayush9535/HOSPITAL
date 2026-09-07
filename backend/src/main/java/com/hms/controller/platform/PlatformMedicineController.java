package com.hms.controller.platform;

import jakarta.validation.Valid;

import com.hms.entity.MedicineList;
import com.hms.service.hospital.MedicineService;
import com.hms.service.platform.PlatformMedicineListService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/platform/medicines")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformMedicineController {

    @Autowired
    private MedicineService medicineService;

    @Autowired
    private PlatformMedicineListService platformMedicineListService;

    /**
     * Get medicines filtered by tenant type (HOSPITAL, CLINIC, PHARMACY).
     */
    @GetMapping
    public ResponseEntity<?> getMedicines(
            @RequestParam(required = false) String hospitalType,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            // If hospitalType is provided, use isolated service
            if (hospitalType != null && !hospitalType.isEmpty()) {
                Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
                Page<MedicineList> result = platformMedicineListService.searchMedicinesByType(hospitalType, search, pageable);
                return ResponseEntity.ok(result);
            }

            // Otherwise, get all medicines (backward compatibility)
            Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
            return ResponseEntity.ok(medicineService.getPlatformMedicines(search, pageable));
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @PostMapping
    public ResponseEntity<?> createMedicine(
            @RequestParam(required = false) String hospitalType,
            @Valid @RequestBody MedicineList medicine) {
        try {
            // Typed catalog (legacy per-tenant-type isolation)
            if (hospitalType != null && !hospitalType.isEmpty()) {
                medicine.setHospitalType(hospitalType);
                MedicineList result = platformMedicineListService.createMedicine(
                    hospitalType,
                    medicine.getName(),
                    medicine.getType()
                );
                return ResponseEntity.ok(result);
            }
            // Global catalog — shared by hospital, clinic and pharmacy searches
            return ResponseEntity.ok(medicineService.addCatalogMedicine(medicine));
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateMedicine(
            @PathVariable Long id,
            @RequestParam(required = false) String hospitalType,
            @RequestBody MedicineList request) {
        try {
            if (hospitalType != null && !hospitalType.isEmpty()) {
                MedicineList result = platformMedicineListService.updateMedicine(
                    id,
                    hospitalType,
                    request.getName(),
                    request.getType()
                );
                return ResponseEntity.ok(result);
            }
            // Global catalog update (no tenant-type isolation)
            return ResponseEntity.ok(medicineService.updateCatalogMedicine(id, request));
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteMedicine(
            @PathVariable Long id,
            @RequestParam(required = false) String hospitalType) {
        try {
            if (hospitalType != null && !hospitalType.isEmpty()) {
                platformMedicineListService.deleteMedicine(id, hospitalType);
            } else {
                // Global catalog delete (no tenant-type isolation)
                medicineService.deleteCatalogMedicine(id);
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @PostMapping("/import-csv")
    public ResponseEntity<?> importCsv(@RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> result = medicineService.importCatalogCsv(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }
}

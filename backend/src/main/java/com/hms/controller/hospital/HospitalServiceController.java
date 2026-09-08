package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.HospitalServiceDTO;
import com.hms.entity.HospitalServiceEntity;
import com.hms.service.hospital.HospitalServiceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/hospital", "/clinic", "/pharmacy"})
public class HospitalServiceController {

    @Autowired
    private HospitalServiceService serviceService;

    @Autowired
    private com.hms.repository.InventoryItemRepository inventoryItemRepository;

    @Autowired
    private com.hms.security.SecurityContextHelper securityHelper;

    private HospitalServiceDTO toDto(HospitalServiceEntity s) {
        return new HospitalServiceDTO(
                s.getId(), s.getName(), s.getCharge(),
                serviceService.getMasterItemIdsForService(s.getId()),
                serviceService.getItemNamesForService(s.getId()));
    }

    /**
     * Global master item list for this tenant (read-only for hospital roles).
     *
     * The platform's Inventory Items tab always sends a hospitalType, so it writes the catalog
     * to `inventory_items` (tenant-typed). This endpoint used to read `inventory_master_items`
     * — a legacy, now-unused table — so the Service Lookup's item search always came back
     * empty no matter what the platform admin added. Read the catalog the platform actually
     * writes, scoped to the caller's tenant type so a clinic never sees hospital items.
     */
    @GetMapping("/inventory-master")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> listMasterItems() {
        String tenantType = currentTenantType();
        List<com.hms.entity.InventoryItem> items = (tenantType == null)
                ? inventoryItemRepository.findAll()
                : inventoryItemRepository.findByHospitalType(tenantType);

        return ResponseEntity.ok(items.stream()
                // Only the platform-owned global catalog rows (hospital_id IS NULL); a
                // hospital's own stock items live in the same table but are hospital-scoped.
                .filter(i -> i.getHospitalId() == null)
                .filter(i -> !Boolean.FALSE.equals(i.getIsActive()))
                .sorted(java.util.Comparator.comparing(
                        com.hms.entity.InventoryItem::getName,
                        java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .collect(Collectors.toList()));
    }

    /** Tenant type (HOSPITAL / CLINIC / PHARMACY) of the caller, from the JWT claim. */
    private String currentTenantType() {
        com.hms.security.UserAuthenticationDetails d = securityHelper.getCurrentUserDetails();
        return d != null ? d.getHospitalType() : null;
    }

    // --- Per-hospital services ---
    @GetMapping("/services")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<List<HospitalServiceDTO>> listServices() {
        List<HospitalServiceDTO> dtos = serviceService.listServices().stream()
                .map(this::toDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/services")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> createService(@Valid @RequestBody HospitalServiceDTO dto) {
        try {
            HospitalServiceEntity saved = serviceService.createService(dto.getName(), dto.getCharge(), dto.getMasterItemIds());
            return ResponseEntity.ok(toDto(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/services/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> updateService(@PathVariable Long id, @Valid @RequestBody HospitalServiceDTO dto) {
        try {
            HospitalServiceEntity saved = serviceService.updateService(id, dto.getName(), dto.getCharge(), dto.getMasterItemIds());
            return ResponseEntity.ok(toDto(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (RuntimeException e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @DeleteMapping("/services/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> deleteService(@PathVariable Long id) {
        try {
            serviceService.deleteService(id);
            return ResponseEntity.ok("Service deleted successfully");
        } catch (RuntimeException e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }
}

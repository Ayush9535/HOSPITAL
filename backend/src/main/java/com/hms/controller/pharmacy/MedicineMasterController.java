package com.hms.controller.pharmacy;

import java.util.Map;

import com.hms.dto.pharmacy.MedicineMasterRequest;
import com.hms.service.pharmacy.MedicineMasterService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pharmacy")
public class MedicineMasterController {

    @Autowired
    private MedicineMasterService service;

    @PostMapping("/medicines")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> create(@Valid @RequestBody MedicineMasterRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @GetMapping("/medicines")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(service.searchAndList(search, pageable));
    }

    @GetMapping("/medicines/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @PutMapping("/medicines/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_ADMIN', 'HOSPITAL_ADMIN', 'INVENTORY_MANAGER')")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody MedicineMasterRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    // Fast lookup APIs
    @GetMapping("/search/medicines")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> search(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(service.searchAndList(query, pageable));
    }

    @GetMapping("/autocomplete/medicines")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> autocomplete(@RequestParam("q") String query) {
        return ResponseEntity.ok(service.autocomplete(query));
    }

    /** Search the platform medicine catalog (name + type) for the purchase form. */
    @GetMapping("/catalog/search")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> searchPlatformCatalog(@RequestParam("q") String query) {
        return ResponseEntity.ok(service.searchPlatformCatalog(query));
    }

    @PatchMapping("/medicines/{id}/status")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_ADMIN', 'HOSPITAL_ADMIN', 'INVENTORY_MANAGER')")
    public ResponseEntity<?> toggleStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> payload) {
        Boolean isActive = payload.get("isActive");
        if (isActive == null) {
            return ResponseEntity.badRequest().body("Missing isActive flag");
        }
        service.toggleStatus(id, isActive);
        return ResponseEntity.ok().build();
    }

}

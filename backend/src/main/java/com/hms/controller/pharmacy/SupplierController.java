package com.hms.controller.pharmacy;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.net.URI;

import com.hms.dto.pharmacy.SupplierRequest;
import com.hms.service.pharmacy.SupplierService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pharmacy/suppliers")
public class SupplierController {

    @Autowired
    private SupplierService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> create(@Valid @RequestBody SupplierRequest req) {
        var created = service.createSupplier(req);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(service.getAll(search, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody SupplierRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}

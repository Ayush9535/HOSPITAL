package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.entity.HospitalInventory;
import com.hms.entity.InventoryItem;
import com.hms.security.RequireModule;
import com.hms.service.hospital.HospitalInventoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/hospital/hospital-inventory", "/clinic/hospital-inventory", "/pharmacy/hospital-inventory"})
@RequireModule("HOSPITAL_INVENTORY")
public class HospitalInventoryController {

    @Autowired
    private HospitalInventoryService hospitalInventoryService;

    // --- Purchase History Management ---

    @GetMapping("/purchases")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<List<com.hms.entity.HospitalInventoryPurchase>> getHospitalInventoryPurchases() {
        return ResponseEntity.ok(hospitalInventoryService.getHospitalInventoryPurchases());
    }

    @PostMapping("/purchases")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> addHospitalInventoryPurchase(@RequestBody com.hms.entity.HospitalInventoryPurchase purchase) {
        return ResponseEntity.ok(hospitalInventoryService.addHospitalInventoryPurchase(purchase));
    }

    // --- Active Stock Inventory CRUD ---

    @GetMapping("/inventory")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<List<HospitalInventory>> getInventoryItems() {
        return ResponseEntity.ok(hospitalInventoryService.getInventoryItems());
    }

    @PostMapping("/inventory")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> addInventoryItem(@Valid @RequestBody HospitalInventory stock) {
        return ResponseEntity.ok(hospitalInventoryService.addInventoryItem(stock));
    }

    @PutMapping("/inventory/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> updateInventoryItem(@PathVariable Long id, @Valid @RequestBody HospitalInventory stock) {
        return ResponseEntity.ok(hospitalInventoryService.updateInventoryItem(id, stock));
    }

    @DeleteMapping("/inventory/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> deleteInventoryItem(@PathVariable Long id) {
        hospitalInventoryService.deleteInventoryItem(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<List<HospitalInventory>> getLowStockItems() {
        return ResponseEntity.ok(hospitalInventoryService.getLowStockItems());
    }
}

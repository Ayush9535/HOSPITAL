package com.hms.controller.pharmacy;

import jakarta.validation.Valid;

import com.hms.dto.pharmacy.PurchaseRequest;
import com.hms.entity.pharmacy.PurchaseInvoice;
import com.hms.service.pharmacy.PurchaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pharmacy/purchases")
@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','PHARMACIST')")
public class PurchaseController {

    @Autowired
    private PurchaseService purchaseService;

    @PostMapping
    public ResponseEntity<PurchaseInvoice> create(@Valid @RequestBody PurchaseRequest req) {
        return ResponseEntity.ok(purchaseService.createPurchase(req));
    }

    @GetMapping
    public ResponseEntity<Page<PurchaseInvoice>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(purchaseService.listInvoices(PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PurchaseInvoice> get(@PathVariable Long id) {
        return ResponseEntity.ok(purchaseService.getInvoice(id));
    }

    @PostMapping("/{id}/post")
    public ResponseEntity<PurchaseInvoice> post(@PathVariable Long id) {
        return ResponseEntity.ok(purchaseService.postInvoice(id));
    }
}

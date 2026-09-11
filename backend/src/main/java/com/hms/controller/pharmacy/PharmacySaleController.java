package com.hms.controller.pharmacy;

import jakarta.validation.Valid;

import com.hms.exception.ResourceNotFoundException;
 
import com.hms.dto.pharmacy.PharmacySaleRequest;
import com.hms.entity.Hospital;
import com.hms.entity.Patient;
import com.hms.entity.pharmacy.PharmacySale;
import com.hms.service.pharmacy.PharmacySaleService;
import com.hms.service.PdfService;
import com.hms.repository.HospitalRepository;
import com.hms.service.hospital.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
 
@RestController
@RequestMapping("/pharmacy/sales")
public class PharmacySaleController {
 
    @Autowired
    private PharmacySaleService saleService;

    @Autowired
    private PdfService pdfService;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private PatientService patientService;
 
    @PostMapping
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> createSale(@Valid @RequestBody PharmacySaleRequest request) {
        return ResponseEntity.ok(saleService.createSale(request));
    }
 
    @GetMapping
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getSalesHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(saleService.getSalesHistory(PageRequest.of(page, size)));
    }
 
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getSaleDetails(@PathVariable Long id) {
        return ResponseEntity.ok(saleService.getSaleDetails(id));
    }
 
    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> downloadReceipt(@PathVariable Long id) {
        PharmacySale sale = saleService.getSaleDetails(id);

        Hospital hospital = hospitalRepository.findById(sale.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        Patient patient = null;
        if (sale.getPatientId() != null) {
            patient = patientService.getPatientById(sale.getPatientId());
        }

        java.io.ByteArrayInputStream pdf = pdfService.generatePharmacySaleReceiptPdf(hospital, patient, sale);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "inline; filename=receipt_" + sale.getBillNumber() + ".pdf");

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(new InputStreamResource(pdf));
    }

    @Autowired
    private com.hms.security.SecurityContextHelper securityHelper;

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getDashboardStats() {
        return ResponseEntity.ok(saleService.getDashboardStats());
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> searchSaleByBillNumber(@RequestParam String billNumber) {
        return ResponseEntity.ok(saleService.findByBillNumber(billNumber, securityHelper.getCurrentHospitalId()));
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> processPatientReturn(@PathVariable Long id, @RequestBody java.util.List<java.util.Map<String, Object>> returnItems) {
        return ResponseEntity.ok(saleService.processPatientReturn(id, returnItems));
    }
}

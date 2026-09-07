package com.hms.controller.pharmacy;

import com.hms.service.pharmacy.PharmacyReportsService;
import com.hms.entity.pharmacy.PharmacySale;
import com.hms.security.SecurityContextHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/pharmacy/reports")
public class PharmacyReportsController {

    @Autowired
    private PharmacyReportsService reportsService;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private SecurityContextHelper securityHelper;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getReportsDashboard() {
        return ResponseEntity.ok(reportsService.getReportsDashboard());
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<byte[]> exportLedgerCsv() {
        Long hospitalId = securityHelper.getCurrentHospitalId();

        List<PharmacySale> sales = em.createQuery(
            "SELECT s FROM PharmacySale s WHERE s.hospitalId = :hospitalId ORDER BY s.createdAt DESC", PharmacySale.class)
            .setParameter("hospitalId", hospitalId)
            .getResultList();

        StringBuilder csv = new StringBuilder();
        // CSV Header
        csv.append("Date,Invoice Number,Patient Name,Subtotal (INR),GST Tax Amount (INR),Net Total (INR),Payment Method,Payment Status\n");

        // CSV Rows
        for (PharmacySale sale : sales) {
            csv.append(sale.getCreatedAt() != null ? sale.getCreatedAt().toString().replace("T", " ") : "N/A").append(",");
            csv.append(sale.getBillNumber() != null ? sale.getBillNumber() : "N/A").append(",");
            csv.append(sale.getPatientName() != null ? sale.getPatientName().replace(",", " ") : "Walk-in").append(",");
            csv.append(sale.getSubtotal() != null ? sale.getSubtotal() : "0").append(",");
            csv.append(sale.getTaxAmount() != null ? sale.getTaxAmount() : "0").append(",");
            csv.append(sale.getNetAmount() != null ? sale.getNetAmount() : "0").append(",");
            csv.append(sale.getPaymentMethod() != null ? sale.getPaymentMethod() : "N/A").append(",");
            csv.append(sale.getPaymentStatus() != null ? sale.getPaymentStatus() : "N/A").append("\n");
        }

        byte[] csvBytes = csv.toString().getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", "pharmacy_tax_ledger.csv");
        headers.setContentLength(csvBytes.length);

        return new ResponseEntity<>(csvBytes, headers, HttpStatus.OK);
    }
}

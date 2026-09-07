package com.hms.service;

import com.hms.entity.*;
import com.hms.entity.pharmacy.PharmacySale;
import com.hms.service.pdf.BillingPdfService;
import com.hms.service.pdf.ClinicalPdfService;
import com.hms.service.pdf.ReportPdfService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * @deprecated Use the focused services in com.hms.service.pdf:
 *   {@link BillingPdfService}, {@link ClinicalPdfService}, {@link ReportPdfService}.
 *   This facade exists only for backward compatibility with existing controllers.
 */
@Deprecated
@Service
public class PdfService {

    @Autowired
    private BillingPdfService billingPdfService;

    @Autowired
    private ClinicalPdfService clinicalPdfService;

    @Autowired
    private ReportPdfService reportPdfService;

    /**
     * Merge several PDFs into one, page after page. Used to print the consultation documents
     * (case paper, bill, prescription) as a single multi-page job — a single print dialog is
     * reliable, whereas firing one dialog per document is not. Null/empty inputs are skipped.
     */
    public ByteArrayInputStream mergePdfs(List<byte[]> pdfs) {
        com.lowagie.text.Document document = new com.lowagie.text.Document();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            com.lowagie.text.pdf.PdfCopy copy = new com.lowagie.text.pdf.PdfCopy(document, out);
            document.open();
            for (byte[] pdf : pdfs) {
                if (pdf == null || pdf.length == 0) continue;
                com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
                for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                    copy.addPage(copy.getImportedPage(reader, i));
                }
                copy.freeReader(reader);
                reader.close();
            }
            document.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed to merge PDFs", e);
        }
        return new ByteArrayInputStream(out.toByteArray());
    }

    public ByteArrayInputStream generatePrescriptionPdf(
            Hospital hospital,
            Doctor doctor,
            Patient patient,
            MedicalRecord medicalRecord,
            List<Prescription> prescriptions) {
        return clinicalPdfService.generatePrescriptionPdf(hospital, doctor, patient, medicalRecord, prescriptions);
    }

    public ByteArrayInputStream generateBillingReceiptPdf(Hospital hospital, Patient patient, Billing billing) {
        return billingPdfService.generateBillingReceiptPdf(hospital, patient, billing);
    }

    public ByteArrayInputStream generatePharmacySaleReceiptPdf(Hospital hospital, Patient patient, PharmacySale sale) {
        return billingPdfService.generatePharmacySaleReceiptPdf(hospital, patient, sale);
    }

    public ByteArrayInputStream generateMedicinesListPdf(
            Hospital hospital,
            Doctor doctor,
            Patient patient,
            String title,
            String customNo,
            java.time.LocalDateTime createdAt,
            List<String[]> itemsList) {
        return reportPdfService.generateMedicinesListPdf(hospital, doctor, patient, title, customNo, createdAt, itemsList);
    }

    public ByteArrayInputStream generateIpdPrescriptionPdf(
            Hospital hospital,
            Patient patient,
            IpdAdmission ipd,
            List<Prescription> prescriptions) {
        return clinicalPdfService.generateIpdPrescriptionPdf(hospital, patient, ipd, prescriptions);
    }

    public ByteArrayInputStream generatePatientActivityPdf(
            Hospital hospital,
            java.time.LocalDate date,
            java.util.List<java.util.Map<String, Object>> activities) {
        return reportPdfService.generatePatientActivityPdf(hospital, date, activities);
    }

    public ByteArrayInputStream generateCasePaperPdf(
            Hospital hospital,
            Doctor doctor,
            Patient patient,
            Opd opd,
            MedicalRecord medicalRecord) {
        return generateCasePaperPdf(hospital, doctor, patient, opd, medicalRecord, java.util.List.of());
    }

    public ByteArrayInputStream generateCasePaperPdf(
            Hospital hospital,
            Doctor doctor,
            Patient patient,
            Opd opd,
            MedicalRecord medicalRecord,
            List<com.hms.entity.LabOrder> labOrders) {
        return clinicalPdfService.generateCasePaperPdf(hospital, doctor, patient, opd, medicalRecord, labOrders);
    }

    public ByteArrayInputStream generatePatientsReportPdf(
            Hospital hospital,
            java.time.LocalDate date,
            java.util.List<Patient> patients) {
        return reportPdfService.generatePatientsReportPdf(hospital, date, patients);
    }

    public ByteArrayInputStream generateOpdReportPdf(
            Hospital hospital,
            java.time.LocalDate date,
            java.util.List<Opd> opds,
            String reportType) {
        return reportPdfService.generateOpdReportPdf(hospital, date, opds, reportType);
    }
}

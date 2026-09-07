package com.hms.service.pdf;

import com.hms.entity.*;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ClinicalPdfService {

    private static final Logger logger = LoggerFactory.getLogger(ClinicalPdfService.class);

    @Autowired
    private com.hms.repository.OpdRepository opdRepository;

    @Autowired
    private com.hms.repository.DoctorRepository doctorRepository;

    @Autowired
    private PdfLayoutHelper helper;

    @Autowired
    private com.hms.service.hospital.VitalSettingsService vitalSettingsService;

    private static final com.fasterxml.jackson.databind.ObjectMapper VITALS_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * The vitals to print for this OPD: the hospital's enabled vitals, in order,
     * as [header, value] pairs. Built-ins read their typed Opd column; custom
     * vitals are read from the opd.custom_vitals JSON. Values default to "--".
     */
    private java.util.List<String[]> resolveVitalsForPrint(Hospital hospital, com.hms.entity.Opd opd) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (hospital == null || hospital.getId() == null) return out;

        java.util.Map<String, String> customs = java.util.Collections.emptyMap();
        if (opd.getCustomVitals() != null && !opd.getCustomVitals().isBlank()) {
            try {
                customs = VITALS_JSON.readValue(opd.getCustomVitals(),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {});
            } catch (Exception e) {
                logger.debug("Could not parse custom vitals for OPD {}", opd.getId(), e);
            }
        }

        java.util.List<java.util.Map<String, Object>> enabled;
        try {
            enabled = vitalSettingsService.enabledVitalsFor(hospital.getId());
        } catch (Exception e) {
            logger.warn("Could not load vitals config; printing none", e);
            return out;
        }

        for (java.util.Map<String, Object> v : enabled) {
            String key = (String) v.get("key");
            String label = (String) v.get("label");
            String unit = (String) v.get("unit");
            boolean isCustom = Boolean.TRUE.equals(v.get("isCustom"));

            String value;
            if (isCustom) {
                value = customs.get(key);
            } else {
                value = switch (key) {
                    case "BP" -> opd.getBp();
                    case "TEMPERATURE" -> opd.getTemperature() == null ? null : String.valueOf(opd.getTemperature());
                    case "PULSE" -> opd.getPulse() == null ? null : String.valueOf(opd.getPulse());
                    case "HEIGHT" -> opd.getHeight() == null ? null : String.valueOf(opd.getHeight());
                    case "WEIGHT" -> opd.getWeight() == null ? null : String.valueOf(opd.getWeight());
                    case "SPO2" -> opd.getSpo2() == null ? null : String.valueOf(opd.getSpo2());
                    default -> null;
                };
            }
            String header = (unit == null || unit.isBlank()) ? label : label + " (" + unit + ")";
            out.add(new String[] { header, (value == null || value.isBlank()) ? "--" : value });
        }
        return out;
    }

    public ByteArrayInputStream generatePrescriptionPdf(
            Hospital hospital,
            Doctor doctor,
            Patient patient,
            MedicalRecord medicalRecord,
            List<Prescription> prescriptions) {

        Document document = new Document(PageSize.A4, 36, 36, 36, 180);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            helper.addPageBorder(writer);
            document.open();

            // Resolve case number from associated OPD
            String customNo = "-";
            if (medicalRecord != null && medicalRecord.getOpdId() != null) {
                try {
                    // Scoped by the record's own hospital rather than the security context: this
                    // is a rendering service with no principal of its own, and the record being
                    // printed is the authority on which tenant the document belongs to.
                    com.hms.entity.Opd opd = opdRepository
                            .findByIdAndHospitalIdWithPatientAndDoctor(
                                    medicalRecord.getOpdId(), medicalRecord.getHospitalId())
                            .orElse(null);
                    if (opd != null) {
                        customNo = opd.getCaseId();
                    }
                } catch (Exception e) {
                    logger.debug("Could not resolve OPD case number for prescription PDF", e);
                }
            }

            // 1. Premium Patient Header
            String diagnosis = (medicalRecord != null && medicalRecord.getDiagnosis() != null) ? medicalRecord.getDiagnosis() : "-";
            helper.addPremiumPatientHeader(
                    document,
                    hospital,
                    doctor,
                    patient,
                    customNo,
                    (medicalRecord != null && medicalRecord.getCreatedAt() != null) ? medicalRecord.getCreatedAt() : java.time.LocalDateTime.now(),
                    diagnosis,
                    "PRESCRIPTION"
            );

            // 2. Main Medication Table
            PdfPTable rxTable = new PdfPTable(5);
            rxTable.setWidthPercentage(100);
            rxTable.setWidths(new float[]{3f, 1f, 1f, 1.5f, 2.5f});
            rxTable.setSpacingBefore(10f);

            helper.addTableHeaderCell(rxTable, "Medicine");
            helper.addTableHeaderCell(rxTable, "Dosage");
            helper.addTableHeaderCell(rxTable, "Freq");
            helper.addTableHeaderCell(rxTable, "Duration");
            helper.addTableHeaderCell(rxTable, "Instruction");

            if (prescriptions != null && !prescriptions.isEmpty()) {
                for (Prescription p : prescriptions) {
                    helper.addTableCell(rxTable, p.getMedicineName(), false);
                    helper.addTableCell(rxTable, p.getDosage(), false);
                    helper.addTableCell(rxTable, p.getFrequency(), false);
                    helper.addTableCell(rxTable, p.getDuration(), false);
                    helper.addTableCell(rxTable, p.getInstructions(), false);
                }
            } else {
                PdfPCell cell = new PdfPCell(new Phrase("No medications prescribed.", PdfLayoutHelper.NORMAL_FONT));
                cell.setColspan(5);
                cell.setPadding(8f);
                rxTable.addCell(cell);
            }
            document.add(rxTable);

            // 3. Follow Up Section
            if (medicalRecord != null && medicalRecord.getFollowUpDate() != null) {
                document.add(new Paragraph("\n"));
                Paragraph flw = new Paragraph("Follow Up Date: ", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD, PdfLayoutHelper.NAVY_BLUE));
                flw.add(new Chunk(medicalRecord.getFollowUpDate().format(DateTimeFormatter.ofPattern("MMM dd, yyyy")), PdfLayoutHelper.NORMAL_FONT));
                document.add(flw);
            }

            // 4. Fixed Signature Footer
            helper.addPremiumFooter(writer, hospital, patient, customNo, "Prescription Authorized Signature");

            document.close();

        } catch (DocumentException e) {
            throw new RuntimeException("Error generating PDF", e);
        }

        return new ByteArrayInputStream(out.toByteArray());
    }

    public ByteArrayInputStream generateIpdPrescriptionPdf(
            Hospital hospital,
            Patient patient,
            IpdAdmission ipd,
            List<Prescription> prescriptions) {

        Document document = new Document(PageSize.A4, 36, 36, 36, 180);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            helper.addPageBorder(writer);
            document.open();

            // Resolve Doctor associated with this IPD
            Doctor doctor = null;
            if (ipd != null && ipd.getDoctorId() != null) {
                doctor = doctorRepository.findById(ipd.getDoctorId()).orElse(null);
            }

            String ipdNo = (ipd != null) ? ipd.getIpdNumber() : "-";
            java.time.LocalDateTime admissionDateTime = (ipd != null && ipd.getAdmissionDatetime() != null)
                    ? ipd.getAdmissionDatetime()
                    : java.time.LocalDateTime.now();
            String primaryDiag = (ipd != null && ipd.getPrimaryDiagnosis() != null) ? ipd.getPrimaryDiagnosis() : "-";

            // 1. Premium Patient Header
            helper.addPremiumPatientHeader(
                    document,
                    hospital,
                    doctor,
                    patient,
                    ipdNo,
                    admissionDateTime,
                    primaryDiag,
                    "IPD PRESCRIPTION"
            );

            // 2. Main Medication Table
            PdfPTable rxTable = new PdfPTable(5);
            rxTable.setWidthPercentage(100);
            rxTable.setWidths(new float[]{3f, 1f, 1f, 1.5f, 2.5f});
            rxTable.setSpacingBefore(10f);

            helper.addTableHeaderCell(rxTable, "Medicine");
            helper.addTableHeaderCell(rxTable, "Dosage");
            helper.addTableHeaderCell(rxTable, "Freq");
            helper.addTableHeaderCell(rxTable, "Duration");
            helper.addTableHeaderCell(rxTable, "Instruction");

            if (prescriptions != null && !prescriptions.isEmpty()) {
                for (Prescription p : prescriptions) {
                    helper.addTableCell(rxTable, p.getMedicineName(), false);
                    helper.addTableCell(rxTable, p.getDosage(), false);
                    helper.addTableCell(rxTable, p.getFrequency(), false);
                    helper.addTableCell(rxTable, p.getDuration(), false);
                    helper.addTableCell(rxTable, p.getInstructions(), false);
                }
            } else {
                PdfPCell cell = new PdfPCell(new Phrase("No medications prescribed.", PdfLayoutHelper.NORMAL_FONT));
                cell.setColspan(5);
                cell.setPadding(8f);
                rxTable.addCell(cell);
            }
            document.add(rxTable);

            // 3. Fixed Footer Style
            helper.addPremiumFooter(writer, hospital, patient, ipdNo, "Prescription Authorized Signature");

            document.close();

        } catch (DocumentException e) {
            throw new RuntimeException("Error generating IPD Prescription PDF", e);
        }

        return new ByteArrayInputStream(out.toByteArray());
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
            java.util.List<com.hms.entity.LabOrder> labOrders) {

        Document document = new Document(PageSize.A4, 36, 36, 36, 180);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            helper.addPageBorder(writer);
            document.open();

            // Resolve Doctor associated with this OPD if null
            if (doctor == null && opd != null && opd.getDoctor() != null) {
                doctor = opd.getDoctor();
            }

            String diagnosis = "-";
            if (medicalRecord != null && medicalRecord.getDiagnosis() != null && !medicalRecord.getDiagnosis().isEmpty()) {
                diagnosis = medicalRecord.getDiagnosis();
            }

            // 1. Standard Premium Header
            helper.addPremiumPatientHeader(
                    document,
                    hospital,
                    doctor,
                    patient,
                    (opd != null) ? opd.getCaseId() : "-",
                    (opd != null) ? opd.getCreatedAt() : java.time.LocalDateTime.now(),
                    diagnosis,
                    "OPD CASE PAPER / CONSULTATION RECORD"
            );

            // 2. Vitals Signs Section — only the vitals this hospital has switched on,
            //    including any it defined itself.
            if (opd != null) {
                java.util.List<String[]> vitals = resolveVitalsForPrint(hospital, opd); // [header, value]
                if (!vitals.isEmpty()) {
                    Paragraph vitalsTitle = new Paragraph("VITAL SIGNS", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD, PdfLayoutHelper.NAVY_BLUE));
                    vitalsTitle.setSpacingBefore(10f);
                    vitalsTitle.setSpacingAfter(5f);
                    document.add(vitalsTitle);

                    // Chunk into tables of at most 5 columns so any number of vitals lays out cleanly.
                    final int MAX_COLS = 5;
                    for (int start = 0; start < vitals.size(); start += MAX_COLS) {
                        java.util.List<String[]> chunk = vitals.subList(start, Math.min(start + MAX_COLS, vitals.size()));
                        PdfPTable vitalsTable = new PdfPTable(chunk.size());
                        vitalsTable.setWidthPercentage(100);
                        vitalsTable.setSpacingAfter(start + MAX_COLS >= vitals.size() ? 15f : 4f);
                        for (String[] v : chunk) helper.addTableHeaderCell(vitalsTable, v[0]);
                        for (String[] v : chunk) helper.addTableCell(vitalsTable, v[1], false);
                        document.add(vitalsTable);
                    }
                }
            }

            // 3. Clinical Consultation Info
            boolean hasClinicalInfo = false;
            PdfPTable clinicalTable = new PdfPTable(1);
            clinicalTable.setWidthPercentage(100);

            if (opd != null && opd.getProblem() != null && !opd.getProblem().trim().isEmpty()) {
                hasClinicalInfo = true;
                PdfPCell cell = new PdfPCell();
                cell.setBorder(Rectangle.NO_BORDER);
                Paragraph problemTitle = new Paragraph("CHIEF COMPLAINT / REASON FOR VISIT:", PdfLayoutHelper.SMALL_BOLD_FONT);
                problemTitle.setSpacingBefore(5f);
                Paragraph problemVal = new Paragraph(opd.getProblem(), PdfLayoutHelper.NORMAL_FONT);
                problemVal.setSpacingAfter(10f);
                cell.addElement(problemTitle);
                cell.addElement(problemVal);
                clinicalTable.addCell(cell);
            }

            if (medicalRecord != null) {
                if (medicalRecord.getSymptoms() != null && !medicalRecord.getSymptoms().trim().isEmpty()) {
                    hasClinicalInfo = true;
                    PdfPCell cell = new PdfPCell();
                    cell.setBorder(Rectangle.NO_BORDER);
                    Paragraph symTitle = new Paragraph("SYMPTOMS / CHIEF COMPLAINTS:", PdfLayoutHelper.SMALL_BOLD_FONT);
                    symTitle.setSpacingBefore(5f);
                    Paragraph symVal = new Paragraph(medicalRecord.getSymptoms(), PdfLayoutHelper.NORMAL_FONT);
                    symVal.setSpacingAfter(10f);
                    cell.addElement(symTitle);
                    cell.addElement(symVal);
                    clinicalTable.addCell(cell);
                }

                if (medicalRecord.getDiagnosis() != null && !medicalRecord.getDiagnosis().trim().isEmpty()) {
                    hasClinicalInfo = true;
                    PdfPCell cell = new PdfPCell();
                    cell.setBorder(Rectangle.NO_BORDER);
                    Paragraph diagTitle = new Paragraph("DIAGNOSIS / CLINICAL IMPRESSION:", PdfLayoutHelper.SMALL_BOLD_FONT);
                    diagTitle.setSpacingBefore(5f);
                    Paragraph diagVal = new Paragraph(medicalRecord.getDiagnosis(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD, PdfLayoutHelper.NAVY_BLUE));
                    diagVal.setSpacingAfter(10f);
                    cell.addElement(diagTitle);
                    cell.addElement(diagVal);
                    clinicalTable.addCell(cell);
                }

                if (medicalRecord.getTreatmentNotes() != null && !medicalRecord.getTreatmentNotes().trim().isEmpty()) {
                    hasClinicalInfo = true;
                    PdfPCell cell = new PdfPCell();
                    cell.setBorder(Rectangle.NO_BORDER);
                    Paragraph notesTitle = new Paragraph("TREATMENT & CLINICAL NOTES:", PdfLayoutHelper.SMALL_BOLD_FONT);
                    notesTitle.setSpacingBefore(5f);
                    Paragraph notesVal = new Paragraph(medicalRecord.getTreatmentNotes(), PdfLayoutHelper.NORMAL_FONT);
                    notesVal.setSpacingAfter(10f);
                    cell.addElement(notesTitle);
                    cell.addElement(notesVal);
                    clinicalTable.addCell(cell);
                }
            }

            if (hasClinicalInfo) {
                Paragraph clinHeading = new Paragraph("CLINICAL EVALUATION", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD, PdfLayoutHelper.NAVY_BLUE));
                clinHeading.setSpacingBefore(10f);
                clinHeading.setSpacingAfter(5f);
                document.add(clinHeading);
                document.add(clinicalTable);
            }

            // 4. Lab Tests Advised (printed when the doctor ordered any at this consultation)
            if (labOrders != null && !labOrders.isEmpty()) {
                Paragraph labHead = new Paragraph("LAB TESTS ADVISED:", PdfLayoutHelper.SMALL_BOLD_FONT);
                labHead.setSpacingBefore(10f);
                document.add(labHead);
                String tests = labOrders.stream()
                        .map(com.hms.entity.LabOrder::getTestName)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.joining(", "));
                document.add(new Paragraph(tests, PdfLayoutHelper.NORMAL_FONT));
            }

            // 5. Follow-up date (printed when the doctor scheduled one)
            if (medicalRecord != null && medicalRecord.getFollowUpDate() != null) {
                Paragraph flwHead = new Paragraph("FOLLOW UP DATE:", PdfLayoutHelper.SMALL_BOLD_FONT);
                flwHead.setSpacingBefore(10f);
                document.add(flwHead);
                document.add(new Paragraph(
                        medicalRecord.getFollowUpDate().format(DateTimeFormatter.ofPattern("MMM dd, yyyy")),
                        PdfLayoutHelper.NORMAL_FONT));
            }

            // 6. Fixed Bottom Signature Footer
            helper.addPremiumFooter(writer, hospital, patient, (opd != null) ? opd.getCaseId() : "-", "Doctor Authorized Signature");

            document.close();

        } catch (DocumentException e) {
            throw new RuntimeException("Error generating Case Paper PDF", e);
        }

        return new ByteArrayInputStream(out.toByteArray());
    }
}

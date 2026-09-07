package com.hms.service.pdf;

import com.hms.entity.*;
import com.hms.entity.pharmacy.PharmacySale;
import com.hms.entity.pharmacy.PharmacySaleItem;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

@Service
public class BillingPdfService {

    @Autowired
    private com.hms.repository.BillingItemRepository billingItemRepository;

    @Autowired
    private com.hms.repository.BillingMedicineRepository billingMedicineRepository;

    @Autowired
    private com.hms.repository.BillingPaymentRepository billingPaymentRepository;

    @Autowired
    private com.hms.repository.DoctorRepository doctorRepository;

    @Autowired
    private com.hms.repository.MedicalRecordRepository medicalRecordRepository;

    @Autowired
    private PdfLayoutHelper helper;

    public ByteArrayInputStream generateBillingReceiptPdf(Hospital hospital, Patient patient, Billing billing) {
        // Redesigned PDF to look exactly like a premium pre-printed receipt with a fixed bottom section
        Document document = new Document(PageSize.A4, 36, 36, 36, 180);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            helper.addPageBorder(writer);
            document.open();

            // Resolve Doctor associated with this bill
            Doctor doctor = null;
            if (billing.getDoctorId() != null) {
                doctor = doctorRepository.findById(billing.getDoctorId()).orElse(null);
            }
            if (doctor == null && hospital != null) {
                java.util.List<Doctor> doctors = doctorRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(hospital.getId());
                if (doctors != null && !doctors.isEmpty()) {
                    doctor = doctors.get(0);
                }
            }

            // Resolve Diagnosis dynamically from related MedicalRecord
            String diagnosis = "-";
            if (billing.getOpdId() != null) {
                java.util.Optional<com.hms.entity.MedicalRecord> record = medicalRecordRepository.findByOpdId(billing.getOpdId());
                if (record.isPresent() && record.get().getDiagnosis() != null && !record.get().getDiagnosis().isEmpty()) {
                    diagnosis = record.get().getDiagnosis();
                }
            } else if (billing.getIpdAdmissionId() != null) {
                java.util.List<com.hms.entity.MedicalRecord> records = medicalRecordRepository.findByIpdAdmissionIdOrderByCreatedAtDesc(billing.getIpdAdmissionId());
                if (records != null && !records.isEmpty() && records.get(0).getDiagnosis() != null && !records.get(0).getDiagnosis().isEmpty()) {
                    diagnosis = records.get(0).getDiagnosis();
                }
            } else if (billing.getAppointmentId() != null) {
                java.util.Optional<com.hms.entity.MedicalRecord> record = medicalRecordRepository.findByAppointmentId(billing.getAppointmentId());
                if (record.isPresent() && record.get().getDiagnosis() != null && !record.get().getDiagnosis().isEmpty()) {
                    diagnosis = record.get().getDiagnosis();
                }
            }

            // 1. Premium Letterhead Header Table
            PdfPTable headerTable = new PdfPTable(3);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{1.2f, 4f, 1f});

            // Logo Box
            PdfPCell logoCell = new PdfPCell();
            logoCell.setBorder(Rectangle.NO_BORDER);
            logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

            if (hospital != null && hospital.getLogoUrl() != null && !hospital.getLogoUrl().trim().isEmpty()) {
                try {
                    com.lowagie.text.Image logoImg = com.lowagie.text.Image.getInstance(new java.net.URL(hospital.getLogoUrl().trim()));
                    logoImg.scaleToFit(60f, 60f);
                    logoImg.setAlignment(Element.ALIGN_CENTER);
                    logoCell.addElement(logoImg);
                } catch (Exception e) {
                    helper.drawTextFallbackLogo(logoCell, hospital);
                }
            } else {
                helper.drawTextFallbackLogo(logoCell, hospital);
            }
            headerTable.addCell(logoCell);

            // Hospital Details Center
            PdfPCell centerHeader = new PdfPCell();
            centerHeader.setBorder(Rectangle.NO_BORDER);
            centerHeader.setHorizontalAlignment(Element.ALIGN_CENTER);

            String parentOrgText = (hospital != null && hospital.getParentOrganization() != null && !hospital.getParentOrganization().trim().isEmpty())
                    ? hospital.getParentOrganization().trim()
                    : "";
            if (!parentOrgText.isEmpty()) {
                Paragraph gmfText = new Paragraph(parentOrgText, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD, PdfLayoutHelper.NAVY_BLUE));
                gmfText.setAlignment(Element.ALIGN_CENTER);
                centerHeader.addElement(gmfText);
            }
            String rawHospName = (hospital != null && hospital.getName() != null) ? hospital.getName().toUpperCase() : "HOSPITAL";
            Paragraph hospText = new Paragraph(rawHospName, PdfLayoutHelper.RED_TITLE_FONT);
            hospText.setAlignment(Element.ALIGN_CENTER);
            String addressText = (hospital != null && hospital.getAddress() != null) ? hospital.getAddress() : "\"Amrutwel\" Near water Tank, Shikrapur, Tal. Shirur, Dist. Pune.";
            Paragraph addrText = new Paragraph(addressText, FontFactory.getFont(FontFactory.HELVETICA, 8, Font.NORMAL, Color.DARK_GRAY));
            addrText.setAlignment(Element.ALIGN_CENTER);

            centerHeader.addElement(hospText);
            centerHeader.addElement(addrText);
            headerTable.addCell(centerHeader);

            // Right Caduceus Space (Empty / Symmetric padding)
            PdfPCell rightHeader = new PdfPCell();
            rightHeader.setBorder(Rectangle.NO_BORDER);
            headerTable.addCell(rightHeader);

            document.add(headerTable);

            // 2. Doctor credentials block
            PdfPTable docTable = new PdfPTable(2);
            docTable.setWidthPercentage(100);
            docTable.setWidths(new float[]{3.5f, 2f});
            docTable.setSpacingBefore(6f);

            String docName = (doctor != null && doctor.getName() != null) ? doctor.getName().toUpperCase() : "B. B. INGALE";
            String docSpec = (doctor != null && doctor.getSpecialization() != null) ? doctor.getSpecialization() : "M.B.B.S, Diploma in Industrial Health";
            String docPhone = (doctor != null && doctor.getPhone() != null) ? doctor.getPhone() : "9850914919";

            PdfPCell docLeft = new PdfPCell();
            docLeft.setBorder(Rectangle.NO_BORDER);
            Paragraph pName = new Paragraph("Dr. " + docName, PdfLayoutHelper.RED_DOCTOR_FONT);
            Paragraph pSpec = new Paragraph("(" + docSpec + ")", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, Color.DARK_GRAY));
            docLeft.addElement(pName);
            docLeft.addElement(pSpec);
            docTable.addCell(docLeft);

            PdfPCell docRight = new PdfPCell();
            docRight.setBorder(Rectangle.NO_BORDER);
            Paragraph pPhone = new Paragraph("Mob. : " + docPhone, PdfLayoutHelper.SMALL_BOLD_FONT);
            pPhone.setAlignment(Element.ALIGN_RIGHT);
            docRight.addElement(pPhone);
            docTable.addCell(docRight);

            document.add(docTable);

            // Separator Line
            Paragraph borderLine = new Paragraph();
            borderLine.add(new LineSeparator(1.5f, 100, Color.BLACK, Element.ALIGN_CENTER, -1));
            document.add(borderLine);
            document.add(new Paragraph("\n"));

            // 3. Patient Details Table (with under-lined input style)
            PdfPTable patientTable = new PdfPTable(2);
            patientTable.setWidthPercentage(100);
            patientTable.setWidths(new float[]{3f, 2f});
            patientTable.setSpacingAfter(10f);

            // No & Date
            PdfPCell noCell = new PdfPCell();
            noCell.setBorder(Rectangle.NO_BORDER);
            Paragraph noPara = new Paragraph("No. : ", PdfLayoutHelper.NORMAL_FONT);
            String rawCustomId = (billing != null && billing.getCustomId() != null) ? billing.getCustomId() : "-";
            noPara.add(new Chunk(rawCustomId, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Font.BOLD, PdfLayoutHelper.NAVY_BLUE)));
            noCell.addElement(noPara);
            patientTable.addCell(noCell);

            PdfPCell dateCell = new PdfPCell();
            dateCell.setBorder(Rectangle.NO_BORDER);
            String rawDate = (billing != null && billing.getCreatedAt() != null)
                    ? billing.getCreatedAt().format(DateTimeFormatter.ofPattern("dd / MM / yyyy"))
                    : "-";
            Paragraph datePara = new Paragraph("Date :  " + rawDate, PdfLayoutHelper.NORMAL_FONT);
            dateCell.addElement(datePara);
            patientTable.addCell(dateCell);

            // Patient Name (Row 2, colspan 2)
            PdfPCell nameCell = new PdfPCell();
            nameCell.setColspan(2);
            nameCell.setBorder(Rectangle.NO_BORDER);
            nameCell.setPaddingTop(4f);
            nameCell.setPaddingBottom(4f);
            Paragraph namePara = new Paragraph("Name of Patient :  ", PdfLayoutHelper.NORMAL_FONT);
            String patName = (patient != null && patient.getName() != null) ? patient.getName().toUpperCase() : "PATIENT";
            namePara.add(new Chunk(patName, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD, Color.BLACK)));
            nameCell.addElement(namePara);
            nameCell.setBorder(Rectangle.BOTTOM);
            nameCell.setBorderColor(Color.LIGHT_GRAY);
            nameCell.setBorderWidth(0.5f);
            patientTable.addCell(nameCell);

            // Address (Row 3, colspan 2)
            PdfPCell addrCell = new PdfPCell();
            addrCell.setColspan(2);
            addrCell.setBorder(Rectangle.NO_BORDER);
            addrCell.setPaddingTop(4f);
            addrCell.setPaddingBottom(4f);
            Paragraph addrPara = new Paragraph("Address :  ", PdfLayoutHelper.NORMAL_FONT);
            String patAddr = (patient != null && patient.getAddress() != null) ? patient.getAddress() : "-";
            addrPara.add(new Chunk(patAddr, PdfLayoutHelper.NORMAL_FONT));
            addrCell.addElement(addrPara);
            addrCell.setBorder(Rectangle.BOTTOM);
            addrCell.setBorderColor(Color.LIGHT_GRAY);
            addrCell.setBorderWidth(0.5f);
            patientTable.addCell(addrCell);

            // Diagnosis (Row 4, colspan 2)
            PdfPCell diagCell = new PdfPCell();
            diagCell.setColspan(2);
            diagCell.setBorder(Rectangle.NO_BORDER);
            diagCell.setPaddingTop(4f);
            diagCell.setPaddingBottom(4f);
            Paragraph diagPara = new Paragraph("Diagnosis :  ", PdfLayoutHelper.NORMAL_FONT);
            diagPara.add(new Chunk(diagnosis, PdfLayoutHelper.NORMAL_FONT));
            diagCell.addElement(diagPara);
            diagCell.setBorder(Rectangle.BOTTOM);
            diagCell.setBorderColor(Color.LIGHT_GRAY);
            diagCell.setBorderWidth(0.5f);
            patientTable.addCell(diagCell);

            document.add(patientTable);

            // Fetch details to support itemization
            java.util.List<com.hms.entity.BillingItem> items = billingItemRepository != null ? billingItemRepository.findByBillingId(billing.getId()) : null;
            java.util.List<com.hms.entity.BillingMedicine> medicines = billingMedicineRepository != null ? billingMedicineRepository.findByBillingId(billing.getId()) : null;
            java.util.List<com.hms.entity.BillingPayment> payments = billingPaymentRepository != null ? billingPaymentRepository.findByBillingId(billing.getId()) : null;

            // Recalculate totals
            java.math.BigDecimal totalAmt = java.math.BigDecimal.ZERO;
            if (items != null) {
                for (com.hms.entity.BillingItem it : items) {
                    if (it.getAmount() != null) totalAmt = totalAmt.add(it.getAmount());
                }
            }
            if (medicines != null) {
                for (com.hms.entity.BillingMedicine med : medicines) {
                    if (med.getAmount() != null) totalAmt = totalAmt.add(med.getAmount());
                }
            }
            if (totalAmt.compareTo(java.math.BigDecimal.ZERO) == 0 && (items == null || items.isEmpty()) && (medicines == null || medicines.isEmpty())) {
                totalAmt = billing.getAmount() != null ? billing.getAmount() : java.math.BigDecimal.ZERO;
            }

            java.math.BigDecimal paidAmt = java.math.BigDecimal.ZERO;
            if (payments != null && !payments.isEmpty()) {
                for (com.hms.entity.BillingPayment pay : payments) {
                    if (pay.getAmount() != null) paidAmt = paidAmt.add(pay.getAmount());
                }
            } else {
                if ("PAID".equalsIgnoreCase(billing.getPaymentStatus())) {
                    paidAmt = totalAmt;
                }
            }

            java.math.BigDecimal balance = totalAmt.subtract(paidAmt);

            // 4. Dynamic Charges Table
            java.util.List<Object[]> chargeRows = helper.buildDynamicChargeRows(items, medicines);

            // Fallback: if no items and no medicines, but bill has amount, show one row
            if (chargeRows.isEmpty() && totalAmt.compareTo(java.math.BigDecimal.ZERO) > 0) {
                String fallbackDesc = "OPD".equalsIgnoreCase(billing.getBillingType()) ? "Consultation Charges" : "Service Charges";
                chargeRows.add(new Object[]{fallbackDesc, totalAmt});
            }

            PdfPTable table = new PdfPTable(4);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{0.6f, 4.4f, 1.2f, 0.5f});

            // Table Headers
            helper.addTableHeaderCell(table, "S.No.");
            helper.addTableHeaderCell(table, "Fees For");
            helper.addTableHeaderCell(table, "Amount Rs.");
            helper.addTableHeaderCell(table, "Ps.");

            // Render actual charge rows
            int rowIdx = 1;
            for (Object[] row : chargeRows) {
                String desc = (String) row[0];
                java.math.BigDecimal val = (java.math.BigDecimal) row[1];
                String rsStr = "";
                String psStr = "";
                if (val != null && val.compareTo(java.math.BigDecimal.ZERO) > 0) {
                    long rupees = val.longValue();
                    int paise = val.subtract(java.math.BigDecimal.valueOf(rupees)).multiply(java.math.BigDecimal.valueOf(100)).intValue();
                    rsStr = String.valueOf(rupees);
                    psStr = paise == 0 ? "00" : String.format("%02d", paise);
                }
                helper.addTableCell(table, String.valueOf(rowIdx), false);
                helper.addTableCell(table, desc, false);
                helper.addTableCell(table, rsStr, true);
                helper.addTableCell(table, psStr, true);
                rowIdx++;
            }

            document.add(table);

            // 5. Fixed Totals Table at the bottom
            PdfPTable fixedBottomTable = new PdfPTable(4);
            fixedBottomTable.setTotalWidth(523f);
            fixedBottomTable.setWidths(new float[]{0.6f, 4.4f, 1.2f, 0.5f});

            // Total Row
            long totalRupees = totalAmt.longValue();
            int totalPaise = totalAmt.subtract(java.math.BigDecimal.valueOf(totalRupees)).multiply(java.math.BigDecimal.valueOf(100)).intValue();
            String totalRsStr = String.valueOf(totalRupees);
            String totalPsStr = totalPaise == 0 ? "00" : String.format("%02d", totalPaise);

            PdfPCell totalLabelCell = new PdfPCell(new Phrase("Total", PdfLayoutHelper.SMALL_BOLD_FONT));
            totalLabelCell.setColspan(2);
            totalLabelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalLabelCell.setPadding(5f);
            fixedBottomTable.addCell(totalLabelCell);
            PdfPCell totalRsCell = new PdfPCell(new Phrase(totalRsStr, PdfLayoutHelper.SMALL_BOLD_FONT));
            totalRsCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalRsCell.setPadding(5f);
            fixedBottomTable.addCell(totalRsCell);
            PdfPCell totalPsCell = new PdfPCell(new Phrase(totalPsStr, PdfLayoutHelper.SMALL_BOLD_FONT));
            totalPsCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalPsCell.setPadding(5f);
            fixedBottomTable.addCell(totalPsCell);

            // Received Row
            long paidRupees = paidAmt.longValue();
            int paidPaise = paidAmt.subtract(java.math.BigDecimal.valueOf(paidRupees)).multiply(java.math.BigDecimal.valueOf(100)).intValue();
            String paidRsStr = String.valueOf(paidRupees);
            String paidPsStr = paidPaise == 0 ? "00" : String.format("%02d", paidPaise);

            PdfPCell receivedLabelCell = new PdfPCell(new Phrase("Received", PdfLayoutHelper.SMALL_BOLD_FONT));
            receivedLabelCell.setColspan(2);
            receivedLabelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            receivedLabelCell.setPadding(5f);
            fixedBottomTable.addCell(receivedLabelCell);
            PdfPCell paidRsCell = new PdfPCell(new Phrase(paidRsStr, PdfLayoutHelper.SMALL_BOLD_FONT));
            paidRsCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            paidRsCell.setPadding(5f);
            fixedBottomTable.addCell(paidRsCell);
            PdfPCell paidPsCell = new PdfPCell(new Phrase(paidPsStr, PdfLayoutHelper.SMALL_BOLD_FONT));
            paidPsCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            paidPsCell.setPadding(5f);
            fixedBottomTable.addCell(paidPsCell);

            // Balance Row
            long balRupees = balance.longValue();
            int balPaise = balance.subtract(java.math.BigDecimal.valueOf(balRupees)).multiply(java.math.BigDecimal.valueOf(100)).intValue();
            String balRsStr = String.valueOf(balRupees);
            String balPsStr = balPaise == 0 ? "00" : String.format("%02d", balPaise);

            PdfPCell balanceLabelCell = new PdfPCell(new Phrase("Balance", PdfLayoutHelper.SMALL_BOLD_FONT));
            balanceLabelCell.setColspan(2);
            balanceLabelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            balanceLabelCell.setPadding(5f);
            fixedBottomTable.addCell(balanceLabelCell);
            PdfPCell balRsCell = new PdfPCell(new Phrase(balRsStr, PdfLayoutHelper.SMALL_BOLD_FONT));
            balRsCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            balRsCell.setPadding(5f);
            fixedBottomTable.addCell(balRsCell);
            PdfPCell balPsCell = new PdfPCell(new Phrase(balPsStr, PdfLayoutHelper.SMALL_BOLD_FONT));
            balPsCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            balPsCell.setPadding(5f);
            fixedBottomTable.addCell(balPsCell);

            fixedBottomTable.writeSelectedRows(0, -1, 36, 175, writer.getDirectContent());

            // 6. Fixed Signature / Remittance Table at the bottom
            PdfPTable footerTable = new PdfPTable(2);
            footerTable.setTotalWidth(523f);
            footerTable.setWidths(new float[]{3.5f, 2.5f});

            PdfPCell footerLeft = new PdfPCell();
            footerLeft.setBorder(Rectangle.NO_BORDER);
            Paragraph recPara = new Paragraph("Received Rs. :  ", PdfLayoutHelper.NORMAL_FONT);
            if (paidAmt.compareTo(java.math.BigDecimal.ZERO) > 0) {
                recPara.add(new Chunk(helper.convertNumberToWords(paidRupees) + " Only", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Font.BOLD, Color.BLACK)));
            } else {
                recPara.add(new Chunk("_______________________________________", PdfLayoutHelper.NORMAL_FONT));
            }
            footerLeft.addElement(recPara);
            footerTable.addCell(footerLeft);

            PdfPCell footerRight = new PdfPCell();
            footerRight.setBorder(Rectangle.NO_BORDER);
            footerRight.setHorizontalAlignment(Element.ALIGN_RIGHT);

            Paragraph forHospital = new Paragraph("For " + hospital.getName(), PdfLayoutHelper.SMALL_BOLD_FONT);
            forHospital.setAlignment(Element.ALIGN_RIGHT);

            Paragraph sigLine = new Paragraph("\n\n\n___________________________\nAuthorized Signature", PdfLayoutHelper.FOOTER_FONT);
            sigLine.setAlignment(Element.ALIGN_RIGHT);

            footerRight.addElement(forHospital);
            footerRight.addElement(sigLine);
            footerTable.addCell(footerRight);

            footerTable.writeSelectedRows(0, -1, 36, 110, writer.getDirectContent());

            document.close();

        } catch (DocumentException e) {
            throw new RuntimeException("Error generating PDF", e);
        }

        return new ByteArrayInputStream(out.toByteArray());
    }

    public ByteArrayInputStream generatePharmacySaleReceiptPdf(Hospital hospital, Patient patient, PharmacySale sale) {
        Document document = new Document(PageSize.A4, 36, 36, 48, 180);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            helper.addPageBorder(writer);
            document.open();

            // 1. Standard Header
            helper.addStyledHeader(document, hospital, "PHARMACY INVOICE");

            // 2. Metadata Table
            PdfPTable metaTable = new PdfPTable(2);
            metaTable.setWidthPercentage(100);
            metaTable.setSpacingBefore(10f);
            metaTable.setSpacingAfter(15f);

            PdfPTable leftCol = new PdfPTable(2);
            leftCol.setWidths(new float[]{1.2f, 2f});
            helper.addMetaRow(leftCol, "Invoice#", sale.getBillNumber());
            helper.addMetaRow(leftCol, "Date", sale.getCreatedAt() != null ? sale.getCreatedAt().format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")) : "-");
            helper.addMetaRow(leftCol, "Patient ID", "-");
            helper.addMetaRow(leftCol, "Sale Type", "WALK-IN");

            PdfPTable rightCol = new PdfPTable(2);
            rightCol.setWidths(new float[]{1.2f, 2f});
            helper.addMetaRow(rightCol, "Customer Name", sale.getPatientName() != null ? sale.getPatientName() : "Walk-in Patient");
            helper.addMetaRow(rightCol, "Payment Method", sale.getPaymentMethod());
            helper.addMetaRow(rightCol, "Status", sale.getPaymentStatus());

            PdfPCell leftCell = new PdfPCell(leftCol);
            leftCell.setBorder(Rectangle.NO_BORDER);
            metaTable.addCell(leftCell);

            PdfPCell rightCell = new PdfPCell(rightCol);
            rightCell.setBorder(Rectangle.NO_BORDER);
            metaTable.addCell(rightCell);

            document.add(metaTable);

            // 3. Line separator
            Paragraph line = new Paragraph();
            line.add(new LineSeparator(1f, 100, Color.LIGHT_GRAY, Element.ALIGN_CENTER, -2));
            document.add(line);
            document.add(new Paragraph("\n"));

            // 4. Main Medicine Items Table
            PdfPTable itemsTable = new PdfPTable(7);
            itemsTable.setWidthPercentage(100);
            itemsTable.setWidths(new float[]{0.5f, 3.5f, 1.5f, 1.2f, 1.2f, 1.2f, 1.5f});

            helper.addTableHeaderCell(itemsTable, "Sr#");
            helper.addTableHeaderCell(itemsTable, "Medicine / Batch");
            helper.addTableHeaderCell(itemsTable, "Expiry");
            helper.addTableHeaderCell(itemsTable, "Qty");
            helper.addTableHeaderCell(itemsTable, "Price");
            helper.addTableHeaderCell(itemsTable, "GST %");
            helper.addTableHeaderCell(itemsTable, "Total");

            int sr = 1;
            for (PharmacySaleItem item : sale.getItems()) {
                helper.addTableCell(itemsTable, String.valueOf(sr++), false);

                // Medicine Name and Batch
                String medName = item.getMedicineBatch() != null && item.getMedicineBatch().getMedicine() != null
                        ? item.getMedicineBatch().getMedicine().getMedicineName()
                        : "Medicine #" + item.getMedicineId();
                String batchNum = item.getMedicineBatch() != null ? item.getMedicineBatch().getBatchNumber() : "N/A";
                helper.addTableCell(itemsTable, medName + "\n(Batch: " + batchNum + ")", false);

                // Expiry Date
                String expDate = item.getMedicineBatch() != null && item.getMedicineBatch().getExpiryDate() != null
                        ? item.getMedicineBatch().getExpiryDate().toString()
                        : "-";
                helper.addTableCell(itemsTable, expDate, false);

                // Qty, Unit Price, GST%, Total
                helper.addTableCell(itemsTable, item.getQuantity() != null ? item.getQuantity().toString() : "0", true);
                helper.addTableCell(itemsTable, item.getUnitPrice() != null ? String.format("%.2f", item.getUnitPrice()) : "0.00", true);
                helper.addTableCell(itemsTable, item.getTaxPercentage() != null ? item.getTaxPercentage().toString() + "%" : "0%", true);
                helper.addTableCell(itemsTable, item.getTotalAmount() != null ? "INR " + String.format("%.2f", item.getTotalAmount()) : "INR 0.00", true);
            }

            document.add(itemsTable);
            document.add(new Paragraph("\n"));

            // 5. Total Calculations (Right Aligned - Fixed Position)
            PdfPTable summaryTable = new PdfPTable(2);
            summaryTable.setTotalWidth(200f);
            summaryTable.setWidths(new float[]{1.5f, 1.5f});

            // Sequence: subtotal, then GST is ADDED, then the discount is SUBTRACTED, then net.
            helper.addSummaryRow(summaryTable, "Sub Total:", sale.getSubtotal() != null ? "INR " + String.format("%.2f", sale.getSubtotal()) : "INR 0.00");
            helper.addSummaryRow(summaryTable, "GST (Tax):", sale.getTaxAmount() != null ? "+ INR " + String.format("%.2f", sale.getTaxAmount()) : "INR 0.00");
            helper.addSummaryRow(summaryTable, "Discount:", sale.getDiscountAmount() != null ? "- INR " + String.format("%.2f", sale.getDiscountAmount()) : "INR 0.00");
            helper.addSummaryRow(summaryTable, "Net Payable:", sale.getNetAmount() != null ? "INR " + String.format("%.2f", sale.getNetAmount()) : "INR 0.00");

            summaryTable.writeSelectedRows(0, -1, 359, 175, writer.getDirectContent());

            // 6. Signature section (Fixed Position)
            PdfPTable fTable = new PdfPTable(2);
            fTable.setTotalWidth(523f);
            fTable.setWidths(new float[]{3.5f, 2.5f});

            // Empty left cell (bottom-left block removed)
            PdfPCell remCell = new PdfPCell();
            remCell.setBorder(Rectangle.NO_BORDER);
            fTable.addCell(remCell);

            // Sign line section
            PdfPCell sigCell = new PdfPCell();
            sigCell.setBorder(Rectangle.NO_BORDER);
            sigCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
            Paragraph sig = new Paragraph("\n\n______________________\nPharmacist Signature", PdfLayoutHelper.FOOTER_FONT);
            sig.setAlignment(Element.ALIGN_RIGHT);
            sigCell.addElement(sig);
            fTable.addCell(sigCell);

            fTable.writeSelectedRows(0, -1, 36, 110, writer.getDirectContent());

            document.close();

        } catch (DocumentException e) {
            throw new RuntimeException("Error generating Pharmacy Invoice PDF", e);
        }

        return new ByteArrayInputStream(out.toByteArray());
    }
}

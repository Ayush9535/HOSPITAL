package com.hms.service.pdf;

import com.hms.entity.*;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ReportPdfService {
    private static final String DATE_PATTERN = "MMM dd, yyyy";


    @Autowired
    private PdfLayoutHelper helper;

    public ByteArrayInputStream generateMedicinesListPdf(
            Hospital hospital,
            Doctor doctor,
            Patient patient,
            String title,
            String customNo,
            java.time.LocalDateTime createdAt,
            List<String[]> itemsList) {

        Document document = new Document(PageSize.A4, 36, 36, 36, 180);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            helper.addPageBorder(writer);
            document.open();

            // 1. Premium Patient Header
            helper.addPremiumPatientHeader(
                    document,
                    hospital,
                    doctor,
                    patient,
                    customNo,
                    createdAt != null ? createdAt : java.time.LocalDateTime.now(),
                    "-",
                    title
            );

            // 2. Main Medicines Table
            PdfPTable table = new PdfPTable(7);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{0.8f, 3.2f, 1.2f, 1.2f, 1.2f, 2.4f, 1.0f});
            table.setSpacingBefore(10f);

            helper.addTableHeaderCell(table, "S.No.");
            helper.addTableHeaderCell(table, "Medicine / Item Description");
            helper.addTableHeaderCell(table, "Dosage");
            helper.addTableHeaderCell(table, "Freq");
            helper.addTableHeaderCell(table, "Duration");
            helper.addTableHeaderCell(table, "Instruction");
            helper.addTableHeaderCell(table, "Qty");

            if (itemsList != null && !itemsList.isEmpty()) {
                int sr = 1;
                for (String[] row : itemsList) {
                    helper.addTableCell(table, String.valueOf(sr++), false);
                    helper.addTableCell(table, row.length > 0 ? row[0] : "", false);
                    helper.addTableCell(table, row.length > 5 ? row[1] : "", false);
                    helper.addTableCell(table, row.length > 5 ? row[2] : "", false);
                    helper.addTableCell(table, row.length > 5 ? row[3] : "", false);
                    helper.addTableCell(table, row.length > 5 ? row[4] : "", false);
                    helper.addTableCell(table, row.length > 5 ? row[5] : (row.length > 1 ? row[1] : ""), false);
                }
            } else {
                PdfPCell cell = new PdfPCell(new Phrase("No medicines or items recorded.", PdfLayoutHelper.NORMAL_FONT));
                cell.setColspan(7);
                cell.setPadding(8f);
                table.addCell(cell);
            }
            document.add(table);

            // 3. Fixed Footer Style
            helper.addPremiumFooter(writer, hospital, patient, customNo, "Authorized Signature");

            document.close();

        } catch (DocumentException e) {
            throw new RuntimeException("Error generating Medicines List PDF", e);
        }

        return new ByteArrayInputStream(out.toByteArray());
    }

    public ByteArrayInputStream generatePatientActivityPdf(
            Hospital hospital,
            java.time.LocalDate date,
            java.util.List<java.util.Map<String, Object>> activities) {

        Document document = new Document(PageSize.A4, 36, 36, 48, 120);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            helper.addPageBorder(writer);
            document.open();

            // 1. Standard Header
            String dateStr = date.format(DateTimeFormatter.ofPattern(DATE_PATTERN));
            String title = "PATIENT ACTIVITY REPORT - " + dateStr;
            helper.addStyledHeader(document, hospital, title);

            // 2. Main Activities Table
            // Columns: S.No., Patient ID, Patient Name, Type, Time, Doctor, Details
            PdfPTable table = new PdfPTable(7);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{0.6f, 1.2f, 2.0f, 1.3f, 1.1f, 2.0f, 1.8f});

            helper.addTableHeaderCell(table, "Sr#");
            helper.addTableHeaderCell(table, "Patient ID");
            helper.addTableHeaderCell(table, "Patient Name");
            helper.addTableHeaderCell(table, "Type");
            helper.addTableHeaderCell(table, "Time");
            helper.addTableHeaderCell(table, "Doctor Name");
            helper.addTableHeaderCell(table, "Details");

            if (activities != null && !activities.isEmpty()) {
                int sr = 1;
                for (java.util.Map<String, Object> act : activities) {
                    helper.addTableCell(table, String.valueOf(sr++), false);
                    helper.addTableCell(table, (String) act.get("patientId"), false);
                    helper.addTableCell(table, (String) act.get("patientName"), false);
                    helper.addTableCell(table, (String) act.get("activityType"), false);

                    // Format Time
                    String timeStr = "-";
                    Object timeObj = act.get("activityTime");
                    if (timeObj instanceof java.time.LocalDateTime) {
                        timeStr = ((java.time.LocalDateTime) timeObj).format(DateTimeFormatter.ofPattern("hh:mm a"));
                    } else if (timeObj != null) {
                        timeStr = timeObj.toString();
                    }
                    helper.addTableCell(table, timeStr, false);
                    helper.addTableCell(table, (String) act.get("doctorName"), false);
                    helper.addTableCell(table, (String) act.get("details"), false);
                }
            } else {
                PdfPCell cell = new PdfPCell(new Phrase("No patient activities found for this date.", PdfLayoutHelper.NORMAL_FONT));
                cell.setColspan(7);
                cell.setPadding(8f);
                table.addCell(cell);
            }
            document.add(table);

            // 3. Authorized Signature Block (fixed footer)
            helper.addFixedFooter(writer, null, "-", "Report Generated By Admin");

            document.close();

        } catch (DocumentException e) {
            throw new RuntimeException("Error generating Patient Activity PDF", e);
        }

        return new ByteArrayInputStream(out.toByteArray());
    }

    private void addListReportHeader(Document document, Hospital hospital, String title, String dateStr) throws DocumentException {
        PdfPTable headerTable = new PdfPTable(3);
        headerTable.setWidthPercentage(100);
        headerTable.setWidths(new float[]{1.2f, 4f, 1f});

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
        String addressText = (hospital != null && hospital.getAddress() != null) ? hospital.getAddress() : "Hospital Location Address";
        Paragraph addrText = new Paragraph(addressText, FontFactory.getFont(FontFactory.HELVETICA, 8, Font.NORMAL, java.awt.Color.DARK_GRAY));
        addrText.setAlignment(Element.ALIGN_CENTER);

        centerHeader.addElement(hospText);
        centerHeader.addElement(addrText);
        headerTable.addCell(centerHeader);

        PdfPCell rightHeader = new PdfPCell();
        rightHeader.setBorder(Rectangle.NO_BORDER);
        headerTable.addCell(rightHeader);

        document.add(headerTable);

        // Separator Line
        Paragraph borderLine = new Paragraph();
        borderLine.add(new com.lowagie.text.pdf.draw.LineSeparator(1.5f, 100, java.awt.Color.BLACK, Element.ALIGN_CENTER, -1));
        document.add(borderLine);
        document.add(new Paragraph("\n"));

        // Title and Date Table
        PdfPTable titleTable = new PdfPTable(2);
        titleTable.setWidthPercentage(100);
        titleTable.setWidths(new float[]{4.2f, 1.8f});
        titleTable.setSpacingAfter(10f);

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        Paragraph tPara = new Paragraph(title.toUpperCase(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Font.BOLD, PdfLayoutHelper.NAVY_BLUE));
        titleCell.addElement(tPara);
        titleTable.addCell(titleCell);

        PdfPCell dateCell = new PdfPCell();
        dateCell.setBorder(Rectangle.NO_BORDER);
        Paragraph dPara = new Paragraph("Date: " + dateStr, PdfLayoutHelper.NORMAL_FONT);
        dPara.setAlignment(Element.ALIGN_RIGHT);
        dateCell.addElement(dPara);
        titleTable.addCell(dateCell);

        document.add(titleTable);
    }

    public ByteArrayInputStream generatePatientsReportPdf(
            Hospital hospital,
            java.time.LocalDate date,
            java.util.List<Patient> patients) {

        Document document = new Document(PageSize.A4, 36, 36, 48, 120);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            helper.addPageBorder(writer);
            document.open();

            // 1. Header
            String dateStr = (date != null) ? date.format(DateTimeFormatter.ofPattern(DATE_PATTERN)) : "All Time";
            addListReportHeader(document, hospital, "Registered Patients Report", dateStr);

            // 2. Table
            PdfPTable table = new PdfPTable(8);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{0.6f, 1.2f, 2.0f, 0.7f, 0.9f, 1.3f, 2.3f, 1.8f});

            helper.addTableHeaderCell(table, "Sr#");
            helper.addTableHeaderCell(table, "Patient ID");
            helper.addTableHeaderCell(table, "Patient Name");
            helper.addTableHeaderCell(table, "Age");
            helper.addTableHeaderCell(table, "Gender");
            helper.addTableHeaderCell(table, "Phone");
            helper.addTableHeaderCell(table, "Address");
            helper.addTableHeaderCell(table, "Registered Date");

            if (patients != null && !patients.isEmpty()) {
                int sr = 1;
                for (Patient p : patients) {
                    helper.addTableCell(table, String.valueOf(sr++), false);
                    helper.addTableCell(table, p.getCustomId() != null ? p.getCustomId() : p.getPublicId(), false);
                    helper.addTableCell(table, p.getName(), false);
                    helper.addTableCell(table, String.valueOf(p.getAge()), false);
                    helper.addTableCell(table, p.getGender(), false);
                    helper.addTableCell(table, p.getPhone(), false);
                    helper.addTableCell(table, p.getAddress(), false);
                    
                    String regDate = "-";
                    if (p.getCreatedAt() != null) {
                        regDate = p.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm a"));
                    }
                    helper.addTableCell(table, regDate, false);
                }
            } else {
                PdfPCell cell = new PdfPCell(new Phrase("No registered patients found.", PdfLayoutHelper.NORMAL_FONT));
                cell.setColspan(8);
                cell.setPadding(8f);
                table.addCell(cell);
            }
            document.add(table);

            // Footer
            helper.addFixedFooter(writer, null, "-", "Report Generated By Staff");

            document.close();

        } catch (DocumentException e) {
            throw new RuntimeException("Error generating Patients Report PDF", e);
        }

        return new ByteArrayInputStream(out.toByteArray());
    }

    public ByteArrayInputStream generateOpdReportPdf(
            Hospital hospital,
            java.time.LocalDate date,
            java.util.List<Opd> opds,
            String reportType) {

        Document document = new Document(PageSize.A4, 36, 36, 48, 120);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            helper.addPageBorder(writer);
            document.open();

            // 1. Header
            String dateStr = (date != null) ? date.format(DateTimeFormatter.ofPattern(DATE_PATTERN)) : "All Time";
            String title = "OPD registrations report" + (reportType != null ? " (" + reportType + ")" : "");
            addListReportHeader(document, hospital, title, dateStr);

            // 2. Table
            PdfPTable table = new PdfPTable(8);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{0.6f, 1.2f, 1.2f, 2.0f, 2.0f, 1.0f, 1.2f, 1.8f});

            helper.addTableHeaderCell(table, "Sr#");
            helper.addTableHeaderCell(table, "Case ID");
            helper.addTableHeaderCell(table, "Patient ID");
            helper.addTableHeaderCell(table, "Patient Name");
            helper.addTableHeaderCell(table, "Doctor Name");
            helper.addTableHeaderCell(table, "Visit Type");
            helper.addTableHeaderCell(table, "Status");
            helper.addTableHeaderCell(table, "Registered At");

            if (opds != null && !opds.isEmpty()) {
                int sr = 1;
                for (Opd o : opds) {
                    addOpdRow(table, o, sr++);
                }
            } else {
                PdfPCell cell = new PdfPCell(new Phrase("No OPD registrations found.", PdfLayoutHelper.NORMAL_FONT));
                cell.setColspan(8);
                cell.setPadding(8f);
                table.addCell(cell);
            }
            document.add(table);

            // Footer
            helper.addFixedFooter(writer, null, "-", "Report Generated By Staff");

            document.close();

        } catch (DocumentException e) {
            throw new RuntimeException("Error generating OPD Report PDF", e);
        }

        return new ByteArrayInputStream(out.toByteArray());
    }

    /** Renders one OPD registration as a table row (null-safe cells). */
    private void addOpdRow(PdfPTable table, Opd o, int sr) {
        helper.addTableCell(table, String.valueOf(sr), false);
        helper.addTableCell(table, o.getCaseId() != null ? o.getCaseId() : String.valueOf(o.getId()), false);
        Patient pat = o.getPatient();
        helper.addTableCell(table, pat != null ? (pat.getCustomId() != null ? pat.getCustomId() : pat.getPublicId()) : "-", false);
        helper.addTableCell(table, pat != null ? pat.getName() : "-", false);
        helper.addTableCell(table, o.getDoctor() != null ? o.getDoctor().getName() : "-", false);
        helper.addTableCell(table, o.getVisitType() != null ? o.getVisitType().toString() : "-", false);
        helper.addTableCell(table, o.getStatus() != null ? o.getStatus().toString() : "-", false);
        String regDate = "-";
        if (o.getCreatedAt() != null) {
            regDate = o.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm a"));
        }
        helper.addTableCell(table, regDate, false);
    }
}

package com.hms.service.pdf;

import com.hms.entity.Hospital;
import com.hms.entity.Patient;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import org.springframework.stereotype.Service;

import java.awt.*;

@Service
public class PdfLayoutHelper {

    // Standardized Fonts
    protected static final Font TITLE_FONT = FontFactory.getFont(FontFactory.TIMES_BOLD, 22, Font.BOLD, new Color(0, 51, 102));
    protected static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLDOBLIQUE, 16, Font.ITALIC, Color.DARK_GRAY);
    protected static final Font LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.NORMAL, Color.GRAY);
    protected static final Font VALUE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.NORMAL, Color.BLACK);
    protected static final Font TABLE_HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD, Color.WHITE);
    protected static final Font NORMAL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.NORMAL, Color.BLACK);
    protected static final Font FOOTER_FONT = FontFactory.getFont(FontFactory.HELVETICA, 9, Font.NORMAL, Color.GRAY);
    protected static final Font SMALL_BOLD_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD, Color.BLACK);

    protected static final Color NAVY_BLUE = new Color(0, 51, 102);
    protected static final Font RED_TITLE_FONT = FontFactory.getFont(FontFactory.TIMES_BOLD, 22, Font.BOLD, NAVY_BLUE);
    protected static final Font RED_DOCTOR_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Font.BOLD, NAVY_BLUE);

    // Helper: build a dynamic list of charge rows from billing items + medicines
    public java.util.List<Object[]> buildDynamicChargeRows(
            java.util.List<com.hms.entity.BillingItem> items,
            java.util.List<com.hms.entity.BillingMedicine> medicines) {
        // Each entry is: { String description, BigDecimal amount }
        java.util.List<Object[]> rows = new java.util.ArrayList<>();

        if (items != null) {
            for (com.hms.entity.BillingItem it : items) {
                String desc = (it.getDescription() != null && !it.getDescription().trim().isEmpty())
                        ? it.getDescription().trim()
                        : "Service Charge";
                java.math.BigDecimal amt = (it.getAmount() != null) ? it.getAmount() : java.math.BigDecimal.ZERO;
                rows.add(new Object[]{desc, amt});
            }
        }

        // Sum all in-clinic medicines into one row
        if (medicines != null && !medicines.isEmpty()) {
            java.math.BigDecimal medTotal = java.math.BigDecimal.ZERO;
            for (com.hms.entity.BillingMedicine med : medicines) {
                if (med.getAmount() != null) medTotal = medTotal.add(med.getAmount());
            }
            if (medTotal.compareTo(java.math.BigDecimal.ZERO) > 0) {
                rows.add(new Object[]{"Medicines by Hospital", medTotal});
            }
        }

        return rows;
    }

    public String convertNumberToWords(long number) {
        if (number == 0) return "Zero";
        String[] units = { "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
                           "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen" };
        String[] tens = { "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety" };

        if (number < 20) return units[(int) number];
        if (number < 100) return tens[(int) (number / 10)] + ((number % 10 != 0) ? " " + units[(int) (number % 10)] : "");
        if (number < 1000) return units[(int) (number / 100)] + " Hundred" + ((number % 100 != 0) ? " and " + convertNumberToWords(number % 100) : "");
        if (number < 100000) return convertNumberToWords(number / 1000) + " Thousand" + ((number % 1000 != 0) ? " " + convertNumberToWords(number % 1000) : "");
        if (number < 10000000) return convertNumberToWords(number / 100000) + " Lakh" + ((number % 100000 != 0) ? " " + convertNumberToWords(number % 100000) : "");
        return convertNumberToWords(number / 10000000) + " Crore" + ((number % 10000000 != 0) ? " " + convertNumberToWords(number % 10000000) : "");
    }

    public void addSummaryRow(PdfPTable table, String label, String value) {
        PdfPCell lCell = new PdfPCell(new Phrase(label, SMALL_BOLD_FONT));
        lCell.setBorder(Rectangle.NO_BORDER);
        lCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        lCell.setPaddingBottom(5f);
        table.addCell(lCell);

        PdfPCell vCell = new PdfPCell(new Phrase(value, SMALL_BOLD_FONT));
        vCell.setBorder(Rectangle.NO_BORDER);
        vCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        vCell.setPaddingBottom(5f);
        table.addCell(vCell);
    }

    public void addFooterForWalkin(Document document, Patient patient, String refNum, String sigLabel) throws DocumentException {
        // Spacer
        for (int i = 0; i < 3; i++) document.add(new Paragraph("\n"));

        // Thick bottom gray bar mimicking remittance
        Paragraph footerBar = new Paragraph();
        footerBar.add(new LineSeparator(8f, 100, Color.DARK_GRAY, Element.ALIGN_CENTER, -2));
        document.add(footerBar);

        // Spacer
        for (int i = 0; i < 2; i++) document.add(new Paragraph("\n"));

        PdfPTable fTable = new PdfPTable(2);
        fTable.setWidthPercentage(100);
        fTable.setSpacingBefore(10f);

        // Remittance section (Removed as per user request)
        PdfPCell remCell = new PdfPCell();
        remCell.setBorder(Rectangle.NO_BORDER);
        fTable.addCell(remCell);

        // Sign line section
        PdfPCell sigCell = new PdfPCell();
        sigCell.setBorder(Rectangle.NO_BORDER);
        sigCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        Paragraph sig = new Paragraph("\n\n______________________\n" + sigLabel, FOOTER_FONT);
        sig.setAlignment(Element.ALIGN_RIGHT);
        sigCell.addElement(sig);
        fTable.addCell(sigCell);

        document.add(fTable);
    }

    /**
     * Shared logic: Generates the corporate header at top of doc
     */
    public void addStyledHeader(Document document, Hospital hospital, String title) throws DocumentException {
        PdfPTable headerTable = new PdfPTable(2);
        headerTable.setWidthPercentage(100);
        headerTable.setWidths(new float[]{3f, 1.5f});

        // Left side: Name & Address
        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        String hospitalName = (hospital != null && hospital.getName() != null) ? hospital.getName().toUpperCase() : "HOSPITAL";
        left.addElement(new Paragraph(hospitalName, TITLE_FONT));
        String addr = (hospital != null && hospital.getAddress() != null) ? hospital.getAddress() : "Hospital Location Address Unavailable";
        left.addElement(new Paragraph(addr, NORMAL_FONT));
        headerTable.addCell(left);

        // Right side: Contacts
        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        right.setVerticalAlignment(Element.ALIGN_BOTTOM);

        PdfPTable contactSubTable = new PdfPTable(2);
        contactSubTable.setWidths(new float[]{1f, 2.5f});
        String phoneVal = (hospital != null && hospital.getPhone() != null) ? hospital.getPhone() : "-";
        addHeaderContactRow(contactSubTable, "Phone:", phoneVal);
        String emailDomain = (hospital != null && hospital.getName() != null) ? hospital.getName().toLowerCase().replaceAll("\\s+", "") : "hospital";
        addHeaderContactRow(contactSubTable, "E-mail:", "support@" + emailDomain + ".com");
        right.addElement(contactSubTable);
        headerTable.addCell(right);

        document.add(headerTable);
        document.add(new Paragraph("\n"));

        // The Big Gray Italic Title
        Paragraph subtitlePara = new Paragraph(title, SUBTITLE_FONT);
        subtitlePara.setSpacingAfter(10f);
        document.add(subtitlePara);
    }

    public void addHeaderContactRow(PdfPTable table, String label, String value) {
        PdfPCell l = new PdfPCell(new Phrase(label, FOOTER_FONT));
        l.setBorder(Rectangle.NO_BORDER);
        l.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(l);

        PdfPCell v = new PdfPCell(new Phrase(value, NORMAL_FONT));
        v.setBorder(Rectangle.NO_BORDER);
        table.addCell(v);
    }

    /**
     * Helper to add a gray key and black value inside metadata grid
     */
    public void addMetaRow(PdfPTable table, String label, String value) {
        PdfPCell lCell = new PdfPCell(new Phrase(label, LABEL_FONT));
        lCell.setBorder(Rectangle.NO_BORDER);
        lCell.setPaddingBottom(5f);
        table.addCell(lCell);

        PdfPCell vCell = new PdfPCell(new Phrase(value != null ? value : "", VALUE_FONT));
        vCell.setBorder(Rectangle.NO_BORDER);
        vCell.setPaddingBottom(5f);
        table.addCell(vCell);
    }

    /**
     * Styles a black header cell for main data table
     */
    public void addTableHeaderCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, TABLE_HEADER_FONT));
        cell.setBackgroundColor(Color.BLACK);
        cell.setPaddingTop(6f);
        cell.setPaddingBottom(6f);
        cell.setPaddingLeft(4f);
        cell.setBorder(Rectangle.NO_BORDER);
        table.addCell(cell);
    }

    public void addTableCell(PdfPTable table, String text, boolean alignRight) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", NORMAL_FONT));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(Color.LIGHT_GRAY);
        cell.setPaddingTop(6f);
        cell.setPaddingBottom(6f);
        cell.setPaddingLeft(4f);
        if (alignRight) {
            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            cell.setPaddingRight(6f);
        }
        table.addCell(cell);
    }

    public void addFooter(Document document, Patient patient, String refNum, String sigLabel) throws DocumentException {
        // Spacer
        for (int i = 0; i < 5; i++) document.add(new Paragraph("\n"));

        // Thick bottom gray bar mimicking remittance
        Paragraph footerBar = new Paragraph();
        footerBar.add(new LineSeparator(8f, 100, Color.DARK_GRAY, Element.ALIGN_CENTER, -2));
        document.add(footerBar);

        // Spacer
        for (int i = 0; i < 2; i++) document.add(new Paragraph("\n"));

        PdfPTable fTable = new PdfPTable(2);
        fTable.setWidthPercentage(100);
        fTable.setSpacingBefore(10f);

        // Remittance section (Removed as per user request)
        PdfPCell remCell = new PdfPCell();
        remCell.setBorder(Rectangle.NO_BORDER);
        fTable.addCell(remCell);

        // Sign line section
        PdfPCell sigCell = new PdfPCell();
        sigCell.setBorder(Rectangle.NO_BORDER);
        sigCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        Paragraph sig = new Paragraph("\n\n______________________\n" + sigLabel, FOOTER_FONT);
        sig.setAlignment(Element.ALIGN_RIGHT);
        sigCell.addElement(sig);
        fTable.addCell(sigCell);

        document.add(fTable);
    }

    public void drawTextFallbackLogo(PdfPCell logoCell, Hospital hospital) {
        String initials = "HOSP";
        if (hospital != null && hospital.getName() != null) {
            String name = hospital.getName().trim();
            String[] parts = name.split("\\s+");
            if (parts.length >= 2) {
                initials = (parts[0].substring(0, Math.min(parts[0].length(), 1)) + parts[1].substring(0, Math.min(parts[1].length(), 1))).toUpperCase();
            } else if (name.length() > 0) {
                initials = name.substring(0, Math.min(name.length(), 4)).toUpperCase();
            }
        }

        PdfPTable logoBorderTable = new PdfPTable(1);
        logoBorderTable.setWidthPercentage(100);

        PdfPCell borderCell = new PdfPCell(new Phrase(initials, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Font.BOLD, NAVY_BLUE)));
        borderCell.setBorderColor(NAVY_BLUE);
        borderCell.setBorderWidth(1.5f);
        borderCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        borderCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        borderCell.setPadding(6f);

        logoBorderTable.addCell(borderCell);
        logoCell.addElement(logoBorderTable);
    }

    public void addPageBorder(PdfWriter writer) {
        writer.setPageEvent(new com.lowagie.text.pdf.PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter writer, Document document) {
                com.lowagie.text.pdf.PdfContentByte cb = writer.getDirectContent();

                // Draw outer border
                cb.setColorStroke(Color.BLACK);
                cb.setLineWidth(1.0f);
                float margin = 20f;
                float x1 = margin;
                float y1 = margin;
                float x2 = document.getPageSize().getWidth() - margin;
                float y2 = document.getPageSize().getHeight() - margin;
                cb.rectangle(x1, y1, x2 - x1, y2 - y1);
                cb.stroke();

                // Draw inner border
                cb.setLineWidth(0.5f);
                float innerMargin = 23f;
                cb.rectangle(innerMargin, innerMargin, document.getPageSize().getWidth() - 2 * innerMargin, document.getPageSize().getHeight() - 2 * innerMargin);
                cb.stroke();
            }
        });
    }

    public void addFixedFooter(PdfWriter writer, Patient patient, String refNum, String sigLabel) throws DocumentException {
        PdfPTable fTable = new PdfPTable(2);
        fTable.setTotalWidth(523f);
        fTable.setWidths(new float[]{3.5f, 2.5f});

        // Remittance section (Removed as per user request)
        PdfPCell remCell = new PdfPCell();
        remCell.setBorder(Rectangle.NO_BORDER);
        fTable.addCell(remCell);

        // Sign line section
        PdfPCell sigCell = new PdfPCell();
        sigCell.setBorder(Rectangle.NO_BORDER);
        sigCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        Paragraph sig = new Paragraph("\n\n______________________\n" + sigLabel, FOOTER_FONT);
        sig.setAlignment(Element.ALIGN_RIGHT);
        sigCell.addElement(sig);
        fTable.addCell(sigCell);

        fTable.writeSelectedRows(0, -1, 36, 110, writer.getDirectContent());
    }

    public void addPremiumPatientHeader(
            Document document,
            Hospital hospital,
            com.hms.entity.Doctor doctor,
            Patient patient,
            String customNo,
            java.time.LocalDateTime createdAt,
            String diagnosis,
            String titleText) throws DocumentException {

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
                drawTextFallbackLogo(logoCell, hospital);
            }
        } else {
            drawTextFallbackLogo(logoCell, hospital);
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
            Paragraph gmfText = new Paragraph(parentOrgText, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD, NAVY_BLUE));
            gmfText.setAlignment(Element.ALIGN_CENTER);
            centerHeader.addElement(gmfText);
        }
        String rawHospName = (hospital != null && hospital.getName() != null) ? hospital.getName().toUpperCase() : "HOSPITAL";
        Paragraph hospText = new Paragraph(rawHospName, RED_TITLE_FONT);
        hospText.setAlignment(Element.ALIGN_CENTER);
        String addressText = (hospital != null && hospital.getAddress() != null) ? hospital.getAddress() : "\"Amrutwel\" Near water Tank, Shikrapur, Tal. Shirur, Dist. Pune.";
        Paragraph addrText = new Paragraph(addressText, FontFactory.getFont(FontFactory.HELVETICA, 8, Font.NORMAL, Color.DARK_GRAY));
        addrText.setAlignment(Element.ALIGN_CENTER);

        centerHeader.addElement(hospText);
        centerHeader.addElement(addrText);
        headerTable.addCell(centerHeader);

        // Right Header Space (Empty / Symmetric padding)
        PdfPCell rightHeader = new PdfPCell();
        rightHeader.setBorder(Rectangle.NO_BORDER);
        headerTable.addCell(rightHeader);

        document.add(headerTable);

        // 2. Doctor credentials block
        PdfPTable docTable = new PdfPTable(2);
        docTable.setWidthPercentage(100);
        docTable.setWidths(new float[]{3.5f, 2f});
        docTable.setSpacingBefore(6f);

        String docName = (doctor != null && doctor.getName() != null) ? doctor.getName().toUpperCase() : "DOCTOR";
        String docSpec = (doctor != null && doctor.getSpecialization() != null) ? doctor.getSpecialization() : "M.B.B.S";
        String docPhone = (doctor != null && doctor.getPhone() != null) ? doctor.getPhone() : "-";

        PdfPCell docLeft = new PdfPCell();
        docLeft.setBorder(Rectangle.NO_BORDER);
        Paragraph pName = new Paragraph("Dr. " + docName, RED_DOCTOR_FONT);
        Paragraph pSpec = new Paragraph("(" + docSpec + ")", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, Color.DARK_GRAY));
        docLeft.addElement(pName);
        docLeft.addElement(pSpec);
        docTable.addCell(docLeft);

        PdfPCell docRight = new PdfPCell();
        docRight.setBorder(Rectangle.NO_BORDER);
        Paragraph pPhone = new Paragraph("Mob. : " + docPhone, SMALL_BOLD_FONT);
        pPhone.setAlignment(Element.ALIGN_RIGHT);
        docRight.addElement(pPhone);
        docTable.addCell(docRight);

        document.add(docTable);

        // Separator Line
        Paragraph borderLine = new Paragraph();
        borderLine.add(new LineSeparator(1.5f, 100, Color.BLACK, Element.ALIGN_CENTER, -1));
        document.add(borderLine);
        document.add(new Paragraph("\n"));

        // Document Title
        if (titleText != null && !titleText.isEmpty()) {
            Paragraph tPara = new Paragraph(titleText.toUpperCase(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Font.BOLD, NAVY_BLUE));
            tPara.setAlignment(Element.ALIGN_CENTER);
            tPara.setSpacingAfter(8f);
            document.add(tPara);
        }

        // 3. Patient Details Table (with under-lined input style)
        PdfPTable patientTable = new PdfPTable(2);
        patientTable.setWidthPercentage(100);
        patientTable.setWidths(new float[]{3f, 2f});
        patientTable.setSpacingAfter(10f);

        // No & Date
        PdfPCell noCell = new PdfPCell();
        noCell.setBorder(Rectangle.NO_BORDER);
        Paragraph noPara = new Paragraph("No. : ", NORMAL_FONT);
        noPara.add(new Chunk(customNo != null ? customNo : "-", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Font.BOLD, NAVY_BLUE)));
        noCell.addElement(noPara);
        patientTable.addCell(noCell);

        PdfPCell dateCell = new PdfPCell();
        dateCell.setBorder(Rectangle.NO_BORDER);
        String rawDate = (createdAt != null)
                ? createdAt.format(java.time.format.DateTimeFormatter.ofPattern("dd / MM / yyyy"))
                : "-";
        Paragraph datePara = new Paragraph("Date :  " + rawDate, NORMAL_FONT);
        dateCell.addElement(datePara);
        patientTable.addCell(dateCell);

        // Patient Name (Row 2, colspan 2)
        PdfPCell nameCell = new PdfPCell();
        nameCell.setColspan(2);
        nameCell.setBorder(Rectangle.NO_BORDER);
        nameCell.setPaddingTop(4f);
        nameCell.setPaddingBottom(4f);
        Paragraph namePara = new Paragraph("Name of Patient :  ", NORMAL_FONT);
        String patName = (patient != null && patient.getName() != null) ? patient.getName().toUpperCase() : "PATIENT";

        // Add Age/Gender inline with Name, e.g. "PATIENT (45 / M)"
        String ageGender = "";
        if (patient != null) {
            ageGender = "  (" + patient.getAge() + " / " + patient.getGender() + ")";
        }
        namePara.add(new Chunk(patName + ageGender, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD, Color.BLACK)));
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
        Paragraph addrPara = new Paragraph("Address :  ", NORMAL_FONT);
        String patAddr = (patient != null && patient.getAddress() != null) ? patient.getAddress() : "-";
        addrPara.add(new Chunk(patAddr, NORMAL_FONT));
        addrCell.addElement(addrPara);
        addrCell.setBorder(Rectangle.BOTTOM);
        addrCell.setBorderColor(Color.LIGHT_GRAY);
        addrCell.setBorderWidth(0.5f);
        patientTable.addCell(addrCell);



        document.add(patientTable);
    }

    public void addPremiumFooter(
            PdfWriter writer,
            Hospital hospital,
            Patient patient,
            String refNum,
            String sigLabel) throws DocumentException {

        // Fixed Signature / Remittance Table at the bottom
        PdfPTable footerTable = new PdfPTable(2);
        footerTable.setTotalWidth(523f);
        footerTable.setWidths(new float[]{3.5f, 2.5f});

        // Remittance section (Removed as per user request)
        PdfPCell footerLeft = new PdfPCell();
        footerLeft.setBorder(Rectangle.NO_BORDER);
        footerTable.addCell(footerLeft);

        PdfPCell footerRight = new PdfPCell();
        footerRight.setBorder(Rectangle.NO_BORDER);
        footerRight.setHorizontalAlignment(Element.ALIGN_RIGHT);

        String hospName = (hospital != null && hospital.getName() != null) ? hospital.getName() : "Hospital";
        Paragraph forHospital = new Paragraph("For " + hospName, SMALL_BOLD_FONT);
        forHospital.setAlignment(Element.ALIGN_RIGHT);

        Paragraph sigLine = new Paragraph("\n\n\n___________________________\n" + sigLabel, FOOTER_FONT);
        sigLine.setAlignment(Element.ALIGN_RIGHT);

        footerRight.addElement(forHospital);
        footerRight.addElement(sigLine);
        footerTable.addCell(footerRight);

        footerTable.writeSelectedRows(0, -1, 36, 110, writer.getDirectContent());
    }
}

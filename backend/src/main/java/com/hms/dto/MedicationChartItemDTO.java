package com.hms.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of the nurse's medication chart: a doctor-authored prescription plus
 * its course progress and today's administration state. Includes STOPPED and
 * COMPLETED orders (never deleted) so the full history is visible.
 */
@Data
public class MedicationChartItemDTO {
    private Long prescriptionId;
    private String medicineName;
    private String dosage;
    private String frequency;
    private String duration;
    private String route;
    private String type;
    private String instructions;

    /** BEFORE_FOOD / AFTER_FOOD / WITH_FOOD / NOT_SPECIFIED, or null on an order predating it. */
    private String foodTiming;

    private String status;          // ACTIVE / STOPPED / COMPLETED
    private LocalDate startDate;
    private Integer durationDays;

    private Integer dayOfCourse;    // 1-based day within the course (null if no start date)
    private boolean courseActive;   // ACTIVE and still within the duration window
    private boolean administeredToday;
    private boolean pending;        // courseActive && not administered today → reminder

    private LocalDateTime lastAdministeredAt;
    private String lastAdministeredStatus;

    // --- Inventory reconciliation -------------------------------------------------------------
    //
    // Advisory only. The chart is a clinical record and every prescription appears on it whatever
    // these say; the nurse administers from the order, not from the stock figure. What these
    // answer is the separate question of whether the facility can also account for the stock.

    /** The linked facility medicine, or null when the order was written as free text. */
    private Long medicineId;

    /**
     * UNLINKED         - no inventory row was chosen; stock is unknown, not zero.
     * LINKED_AVAILABLE - linked, and usable in-date stock exists.
     * LINKED_NO_STOCK  - linked, and every batch is empty, expired or withdrawn.
     *
     * <p>There is deliberately no "insufficient" state here. Insufficiency is a comparison
     * against a quantity, and a prescription carries a dosage as free text ("500mg", "1-0-1") --
     * nothing this code can turn into a number of units without inventing the conversion. It is
     * decided where a real quantity exists, at dispense, and reported there.
     */
    private String inventoryStatus;

    /** Usable units across in-date, active batches. Null when UNLINKED — absent, not zero. */
    private Integer availableQuantity;

    /** Expiry of the batch that would be dispensed first (FEFO). Null when nothing is usable. */
    private LocalDate earliestExpiry;

    /**
     * Units already issued from the pharmacy against this order.
     *
     * <p>Shown so the nurse can see whether the drug has actually reached the ward, which is a
     * different question from whether it has been given. Zero here does not block administering:
     * plenty of facilities issue ward stock without a per-order dispense record.
     */
    private Integer quantityDispensed;
}

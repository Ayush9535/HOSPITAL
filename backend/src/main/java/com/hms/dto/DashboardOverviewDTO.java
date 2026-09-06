package com.hms.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The Hospital Admin Overview, as one bounded read.
 *
 * <p>Every optional block is null when the tenant does not hold the capability, and
 * {@code NON_NULL} keeps the key out of the JSON entirely. That absence is the contract: the
 * dashboard must be able to tell "you do not have Pharmacy" from "your pharmacy sold nothing
 * today", and a zero cannot say both. A block that is present is always fully populated, with
 * real zeros — an owned module with no activity is a legitimate, meaningful zero.
 *
 * <p>Laboratory, radiology and emergency are deliberately absent from this contract rather than
 * present-and-empty. The data model cannot support them honestly yet (no radiology domain at all,
 * and lab orders have no workflow or completion state), so shipping empty cards would invent
 * analytics the hospital cannot act on. Adding them later is purely additive.
 *
 * <p>{@code from}/{@code toExclusive} are business wall-clock instants in {@code timezone}, echoed
 * back so a reader never has to guess which day boundary produced the numbers. The interval is
 * half-open: {@code >= from} and {@code < toExclusive}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardOverviewDTO {

    private String range;
    private LocalDateTime from;
    private LocalDateTime toExclusive;
    private String timezone;

    private Core core;
    private OpdBlock opd;
    private IpdBlock ipd;
    private BedsBlock beds;
    private BillingBlock billing;
    private PharmacyBlock pharmacy;

    /** Always present: these need no module beyond being a hospital. */
    public record Core(long totalRegisteredPatients, long opdConsultations) {}

    /** One day of a trend. Every day in the range is present, including the silent ones. */
    public record Bucket(LocalDate date, long count) {}

    public record VisitTypeCount(String type, long count) {}

    public record SpecialityCount(String speciality, long count) {}

    public record OpdBlock(long count, List<Bucket> trend, List<VisitTypeCount> visitTypes,
                           List<SpecialityCount> busiestSpecialities) {}

    public record IpdBlock(long admissions, List<Bucket> trend) {}

    /**
     * A snapshot, not a range: beds are counted as they stand right now, which is why this block
     * carries its own {@code asOf} instead of borrowing the report's window.
     *
     * <p>{@code usableCapacity} excludes maintenance beds, because a bed under maintenance is not
     * capacity anyone can sell. Cleaning beds stay in the denominator — they are capacity, merely
     * not free this minute — which is also why {@code currentlyAvailable} is narrower than
     * {@code usableCapacity - occupied}. {@code occupancyRate} is null rather than zero when there
     * is no usable capacity: a hospital with no beds has no occupancy, and 0% would read as empty.
     */
    public record BedsBlock(long occupied, long usableCapacity, long currentlyAvailable,
                            long cleaning, long maintenance, long unknownStatusCount,
                            Double occupancyRate, LocalDateTime asOf) {}

    public record BillingBlock(BigDecimal collection, String currency) {}

    public record PharmacyBlock(long pharmacySales) {}

    public String getRange() { return range; }
    public void setRange(String range) { this.range = range; }

    public LocalDateTime getFrom() { return from; }
    public void setFrom(LocalDateTime from) { this.from = from; }

    public LocalDateTime getToExclusive() { return toExclusive; }
    public void setToExclusive(LocalDateTime toExclusive) { this.toExclusive = toExclusive; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public Core getCore() { return core; }
    public void setCore(Core core) { this.core = core; }

    public OpdBlock getOpd() { return opd; }
    public void setOpd(OpdBlock opd) { this.opd = opd; }

    public IpdBlock getIpd() { return ipd; }
    public void setIpd(IpdBlock ipd) { this.ipd = ipd; }

    public BedsBlock getBeds() { return beds; }
    public void setBeds(BedsBlock beds) { this.beds = beds; }

    public BillingBlock getBilling() { return billing; }
    public void setBilling(BillingBlock billing) { this.billing = billing; }

    public PharmacyBlock getPharmacy() { return pharmacy; }
    public void setPharmacy(PharmacyBlock pharmacy) { this.pharmacy = pharmacy; }
}

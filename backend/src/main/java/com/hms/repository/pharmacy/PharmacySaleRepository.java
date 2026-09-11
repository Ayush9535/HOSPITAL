package com.hms.repository.pharmacy;

import com.hms.entity.pharmacy.PharmacySale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PharmacySaleRepository extends JpaRepository<PharmacySale, Long> {
    Page<PharmacySale> findByHospitalIdOrderByCreatedAtDesc(Long hospitalId, Pageable pageable);
    Optional<PharmacySale> findByIdAndHospitalId(Long id, Long hospitalId);
    Optional<PharmacySale> findByBillNumberAndHospitalId(String billNumber, Long hospitalId);

    // Branch-scoped (null branchId => whole hospital, e.g. admin merged view).
    @org.springframework.data.jpa.repository.Query("SELECT s FROM PharmacySale s WHERE s.hospitalId = :hid AND (:branchId IS NULL OR s.branchId = :branchId) ORDER BY s.createdAt DESC")
    Page<PharmacySale> findScopedHistory(@org.springframework.data.repository.query.Param("hid") Long hid, @org.springframework.data.repository.query.Param("branchId") Long branchId, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT s FROM PharmacySale s WHERE s.id = :id AND s.hospitalId = :hid AND (:branchId IS NULL OR s.branchId = :branchId)")
    Optional<PharmacySale> findByIdScoped(@org.springframework.data.repository.query.Param("id") Long id, @org.springframework.data.repository.query.Param("hid") Long hid, @org.springframework.data.repository.query.Param("branchId") Long branchId);

    @org.springframework.data.jpa.repository.Query("SELECT s FROM PharmacySale s WHERE s.billNumber = :billNumber AND s.hospitalId = :hid AND (:branchId IS NULL OR s.branchId = :branchId)")
    Optional<PharmacySale> findByBillNumberScoped(@org.springframework.data.repository.query.Param("billNumber") String billNumber, @org.springframework.data.repository.query.Param("hid") Long hid, @org.springframework.data.repository.query.Param("branchId") Long branchId);

    @org.springframework.data.jpa.repository.Query("SELECT SUM(s.netAmount) FROM PharmacySale s WHERE s.hospitalId = :hospitalId AND s.createdAt BETWEEN :start AND :end")
    java.math.BigDecimal getSumOfSalesBetween(Long hospitalId, java.time.LocalDateTime start, java.time.LocalDateTime end);

    long countByHospitalIdAndCreatedAtBetween(Long hospitalId, java.time.LocalDateTime start, java.time.LocalDateTime end);

    java.util.List<PharmacySale> findByHospitalIdAndCreatedAtAfter(Long hospitalId, java.time.LocalDateTime createdAt);

    /**
     * Dispensing transactions in the window.
     *
     * <p>Counts sales, not sale lines and not units. A single medicine drawn from two batches is
     * two lines but one dispensing event, and pack sizes make unit totals incomparable day to day.
     *
     * <p>No payment-status filter: PharmacySaleService deducts batch stock under lock at creation,
     * so the row itself is the physical dispensing event, and IPD medicine leaves the shelf long
     * before anyone settles the bill. Filtering on PAID would quietly erase inpatient dispensing.
     *
     * <p>Posting status is null-tolerant. The service only ever writes POSTED; a null is a row
     * that predates the column, not a row that was never posted. Written as "not explicitly
     * something else" so that if a DRAFT or VOID state is ever introduced it is excluded by
     * default rather than silently counted.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(s) FROM PharmacySale s WHERE s.hospitalId = :hospitalId "
            + "AND s.createdAt >= :from AND s.createdAt < :toExclusive "
            + "AND (s.postingStatus IS NULL OR s.postingStatus = 'POSTED')")
    long countPostedSalesInRange(
            @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("toExclusive") java.time.LocalDateTime toExclusive);
}

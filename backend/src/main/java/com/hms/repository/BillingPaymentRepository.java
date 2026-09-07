package com.hms.repository;

import com.hms.entity.BillingPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillingPaymentRepository extends JpaRepository<BillingPayment, Long> {
    List<BillingPayment> findByBillingId(Long billingId);
    List<BillingPayment> findByBillingIdIn(List<Long> billingIds);

    /**
     * Money actually collected in the window.
     *
     * <p>Payments, not billings: a bill is what was charged, a payment is what came in. There is no
     * void or reversal state on this table — every row is a recognised receipt — so the sum needs
     * no status filter. COALESCE keeps a hospital with no payments at 0.00 instead of null.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(SUM(bp.amount), 0) FROM BillingPayment bp WHERE bp.hospitalId = :hospitalId "
            + "AND bp.createdAt >= :from AND bp.createdAt < :toExclusive")
    java.math.BigDecimal sumCollectedInRange(
            @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("toExclusive") java.time.LocalDateTime toExclusive);
}

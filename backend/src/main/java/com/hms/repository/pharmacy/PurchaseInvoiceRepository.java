package com.hms.repository.pharmacy;

import com.hms.entity.pharmacy.PurchaseInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PurchaseInvoiceRepository extends JpaRepository<PurchaseInvoice, Long> {
    Page<PurchaseInvoice> findByHospitalIdOrderByCreatedAtDesc(Long hospitalId, Pageable pageable);
    Optional<PurchaseInvoice> findByIdAndHospitalId(Long id, Long hospitalId);

    // Branch-scoped (null branchId => whole hospital, for admin merged view).
    @org.springframework.data.jpa.repository.Query("SELECT p FROM PurchaseInvoice p WHERE p.hospitalId = :hid AND (:branchId IS NULL OR p.branchId = :branchId) ORDER BY p.createdAt DESC")
    Page<PurchaseInvoice> findScopedHistory(@org.springframework.data.repository.query.Param("hid") Long hid, @org.springframework.data.repository.query.Param("branchId") Long branchId, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT p FROM PurchaseInvoice p LEFT JOIN FETCH p.items WHERE p.id = :id AND p.hospitalId = :hid AND (:branchId IS NULL OR p.branchId = :branchId)")
    Optional<PurchaseInvoice> findByIdScoped(@org.springframework.data.repository.query.Param("id") Long id, @org.springframework.data.repository.query.Param("hid") Long hid, @org.springframework.data.repository.query.Param("branchId") Long branchId);
}

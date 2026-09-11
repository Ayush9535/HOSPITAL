package com.hms.repository.pharmacy;

import com.hms.entity.pharmacy.InventoryTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {
    Page<InventoryTransaction> findByMedicineBatchIdOrderByCreatedAtDesc(Long medicineBatchId, Pageable pageable);
    org.springframework.data.domain.Page<InventoryTransaction> findByHospitalIdAndTransactionTypeOrderByCreatedAtDesc(Long hospitalId, String transactionType, org.springframework.data.domain.Pageable pageable);
}

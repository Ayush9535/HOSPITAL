package com.hms.repository.pharmacy;

import com.hms.entity.pharmacy.PurchaseInvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PurchaseInvoiceItemRepository extends JpaRepository<PurchaseInvoiceItem, Long> {
    List<PurchaseInvoiceItem> findByPurchaseInvoiceId(Long purchaseInvoiceId);
}

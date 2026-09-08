package com.hms.repository;

import com.hms.entity.BillingItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BillingItemRepository extends JpaRepository<BillingItem, Long> {
    List<BillingItem> findByBillingId(Long billingId);
    List<BillingItem> findByHospitalIdAndBillingId(Long hospitalId, Long billingId);
    List<BillingItem> findByBillingIdIn(List<Long> billingIds);

}

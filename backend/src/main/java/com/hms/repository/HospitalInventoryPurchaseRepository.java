package com.hms.repository;

import com.hms.entity.HospitalInventoryPurchase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface HospitalInventoryPurchaseRepository extends JpaRepository<HospitalInventoryPurchase, Long> {
    List<HospitalInventoryPurchase> findByHospitalIdOrderByPurchaseDateDesc(Long hospitalId);
}

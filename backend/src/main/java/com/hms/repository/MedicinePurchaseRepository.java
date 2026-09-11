package com.hms.repository;

import com.hms.entity.MedicinePurchase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MedicinePurchaseRepository extends JpaRepository<MedicinePurchase, Long> {
    List<MedicinePurchase> findByHospitalIdOrderByPurchaseDateDesc(Long hospitalId);
}

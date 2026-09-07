package com.hms.repository;

import com.hms.entity.BillingMedicine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillingMedicineRepository extends JpaRepository<BillingMedicine, Long> {

    List<BillingMedicine> findByBillingId(Long billingId);
    List<BillingMedicine> findByHospitalIdAndBillingId(Long hospitalId, Long billingId);
    List<BillingMedicine> findByBillingIdIn(List<Long> billingIds);
}

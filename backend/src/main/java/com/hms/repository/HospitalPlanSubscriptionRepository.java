package com.hms.repository;

import com.hms.entity.HospitalPlanSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HospitalPlanSubscriptionRepository extends JpaRepository<HospitalPlanSubscription, Long> {

    Optional<HospitalPlanSubscription> findByHospitalIdAndIsCurrentTrue(Long hospitalId);

    List<HospitalPlanSubscription> findByPlan_IdAndIsCurrentTrue(Long planId);

    long countByPlan_IdAndIsCurrentTrue(Long planId);

    @Modifying
    @Query("UPDATE HospitalPlanSubscription s SET s.isCurrent = false WHERE s.hospitalId = :hospitalId AND s.isCurrent = true")
    void deactivateCurrentSubscription(@Param("hospitalId") Long hospitalId);
}

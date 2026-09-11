package com.hms.repository;

import com.hms.entity.HospitalType;
import com.hms.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanRepository extends JpaRepository<Plan, Long> {

    Optional<Plan> findByPublicId(String publicId);

    List<Plan> findAllByOrderByCreatedAtDesc();

    List<Plan> findByTypeOrderByCreatedAtDesc(HospitalType type);

    long countByIsActiveTrue();
}

package com.hms.repository;

import com.hms.entity.InitialAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InitialAssessmentRepository extends JpaRepository<InitialAssessment, Long> {
    Optional<InitialAssessment> findByIpdAdmissionId(Long ipdAdmissionId);
}

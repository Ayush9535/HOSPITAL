package com.hms.repository;

import com.hms.entity.DischargeSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DischargeSummaryRepository extends JpaRepository<DischargeSummary, Long> {
    Optional<DischargeSummary> findByIpdAdmissionId(Long ipdAdmissionId);
}

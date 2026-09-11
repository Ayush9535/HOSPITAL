package com.hms.repository;

import com.hms.entity.NurseSubstitution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NurseSubstitutionRepository extends JpaRepository<NurseSubstitution, Long> {
    Optional<NurseSubstitution> findByPublicId(String publicId);
    List<NurseSubstitution> findByReplacementNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(Long replId, LocalDate d1, LocalDate d2);
    List<NurseSubstitution> findByHospitalIdAndToDateGreaterThanEqualOrderByFromDateAsc(Long hospitalId, LocalDate today);
}

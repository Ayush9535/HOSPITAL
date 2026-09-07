package com.hms.repository;

import com.hms.entity.NurseWardAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NurseWardAssignmentRepository extends JpaRepository<NurseWardAssignment, Long> {
    Optional<NurseWardAssignment> findByPublicId(String publicId);
    List<NurseWardAssignment> findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(Long nurseProfileId, LocalDate d1, LocalDate d2);
    List<NurseWardAssignment> findByTempWardIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(Long tempWardId, LocalDate d1, LocalDate d2);
    List<NurseWardAssignment> findByHospitalIdAndToDateGreaterThanEqualOrderByFromDateAsc(Long hospitalId, LocalDate today);
}

package com.hms.repository.pharmacy;

import com.hms.entity.pharmacy.PharmacyBranch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PharmacyBranchRepository extends JpaRepository<PharmacyBranch, Long> {

    List<PharmacyBranch> findByHospitalIdAndIsActiveTrueOrderByCreatedAtAsc(Long hospitalId);

    Optional<PharmacyBranch> findByIdAndHospitalId(Long id, Long hospitalId);

    long countByHospitalIdAndIsActiveTrue(Long hospitalId);
}

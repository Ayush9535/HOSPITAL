package com.hms.repository;

import com.hms.entity.CaseRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CaseRoleRepository extends JpaRepository<CaseRole, Long> {
    List<CaseRole> findByHospitalIdAndIsActiveTrueOrderByLabelAsc(Long hospitalId);
    Optional<CaseRole> findByHospitalIdAndCode(Long hospitalId, String code);
    boolean existsByHospitalIdAndCode(Long hospitalId, String code);
}

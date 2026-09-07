package com.hms.repository;

import com.hms.entity.HospitalFormAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HospitalFormAccessRepository extends JpaRepository<HospitalFormAccess, Long> {
    Optional<HospitalFormAccess> findByHospitalIdAndFormKey(Long hospitalId, String formKey);
    List<HospitalFormAccess> findByHospitalId(Long hospitalId);
}

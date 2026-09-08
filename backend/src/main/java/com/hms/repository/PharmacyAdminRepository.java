package com.hms.repository;

import com.hms.entity.PharmacyAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PharmacyAdminRepository extends JpaRepository<PharmacyAdmin, Long> {
    Optional<PharmacyAdmin> findByEmail(String email);
    Optional<PharmacyAdmin> findByHospitalId(Long hospitalId);
}

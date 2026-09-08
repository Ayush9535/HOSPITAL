package com.hms.repository;

import com.hms.entity.ClinicAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClinicAdminRepository extends JpaRepository<ClinicAdmin, Long> {
    Optional<ClinicAdmin> findByEmail(String email);
    Optional<ClinicAdmin> findByHospitalId(Long hospitalId);
}

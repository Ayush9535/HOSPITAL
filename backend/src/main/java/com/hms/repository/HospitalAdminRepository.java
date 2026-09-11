package com.hms.repository;

import com.hms.entity.HospitalAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface HospitalAdminRepository extends JpaRepository<HospitalAdmin, Long> {
    Optional<HospitalAdmin> findByEmail(String email);
    Optional<HospitalAdmin> findByEmailAndHospitalId(String email, Long hospitalId);
}

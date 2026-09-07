package com.hms.repository;

import com.hms.entity.OtIncharge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OtInchargeProfileRepository extends JpaRepository<OtIncharge, Long> {
    Optional<OtIncharge> findByEmail(String email);
    Optional<OtIncharge> findByEmailAndHospitalId(String email, Long hospitalId);
}

package com.hms.repository;

import com.hms.entity.Pharmacist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PharmacistProfileRepository extends JpaRepository<Pharmacist, Long> {
    Optional<Pharmacist> findByEmail(String email);
    Optional<Pharmacist> findByEmailAndHospitalId(String email, Long hospitalId);
}

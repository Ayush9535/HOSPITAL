package com.hms.repository;

import com.hms.entity.Receptionist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReceptionistProfileRepository extends JpaRepository<Receptionist, Long> {
    Optional<Receptionist> findByEmail(String email);
    Optional<Receptionist> findByEmailAndHospitalId(String email, Long hospitalId);
}

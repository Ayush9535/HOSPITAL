package com.hms.repository;

import com.hms.entity.HospitalFee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface HospitalFeeRepository extends JpaRepository<HospitalFee, Long> {
    List<HospitalFee> findByHospitalIdAndIsActiveTrue(Long hospitalId);
    Optional<HospitalFee> findByIdAndHospitalId(Long id, Long hospitalId);
}

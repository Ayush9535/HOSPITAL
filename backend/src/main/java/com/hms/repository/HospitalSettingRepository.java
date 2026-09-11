package com.hms.repository;

import com.hms.entity.Hospital;
import com.hms.entity.HospitalSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface HospitalSettingRepository extends JpaRepository<HospitalSetting, Long> {

    Optional<HospitalSetting> findByHospital(Hospital hospital);

    Optional<HospitalSetting> findByHospital_Id(Long hospitalId);

    @Modifying
    @Query("UPDATE HospitalSetting s SET s.receptionMode = :receptionMode, s.billingHandler = :billingHandler, s.inClinic = :inClinic WHERE s.hospital.id = :hospitalId")
    void updateByHospitalId(@Param("hospitalId") Long hospitalId,
                            @Param("receptionMode") String receptionMode,
                            @Param("billingHandler") String billingHandler,
                            @Param("inClinic") Boolean inClinic);
}

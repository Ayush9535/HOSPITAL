package com.hms.repository;

import com.hms.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WardRepository extends JpaRepository<Ward, Long> {
    List<Ward> findByHospitalId(Long hospitalId);
    java.util.Optional<Ward> findByWardIdAndHospitalId(Long wardId, Long hospitalId);


    java.util.List<Ward> findByHospitalIdAndInchargeNurseId(Long hospitalId, Long inchargeNurseId);

    /**
     * ICU Phase 2 — the tenant's wards of the given unit types (CareUnitRegistry keys). Used to
     * resolve the ICU board's units without loading every ward and filtering in Java.
     */
    List<Ward> findByHospitalIdAndUnitTypeIn(Long hospitalId, java.util.Collection<String> unitTypes);
}

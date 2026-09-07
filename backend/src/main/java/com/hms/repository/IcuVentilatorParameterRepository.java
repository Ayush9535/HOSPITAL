package com.hms.repository;

import com.hms.entity.IcuVentilatorParameter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** ICU Phase 7 configuration. Every finder is tenant-scoped; nothing resolves a bare id. */
public interface IcuVentilatorParameterRepository
        extends JpaRepository<IcuVentilatorParameter, Long> {

    List<IcuVentilatorParameter> findByHospitalId(Long hospitalId);

    Optional<IcuVentilatorParameter> findByHospitalIdAndParamKey(Long hospitalId, String paramKey);
}

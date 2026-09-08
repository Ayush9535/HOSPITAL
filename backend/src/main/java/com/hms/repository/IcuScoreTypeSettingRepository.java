package com.hms.repository;

import com.hms.entity.IcuScoreTypeSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** ICU Phase 8 configuration. Every finder is tenant-scoped; nothing resolves a bare id. */
public interface IcuScoreTypeSettingRepository extends JpaRepository<IcuScoreTypeSetting, Long> {

    List<IcuScoreTypeSetting> findByHospitalId(Long hospitalId);

    Optional<IcuScoreTypeSetting> findByHospitalIdAndScoreType(Long hospitalId, String scoreType);
}

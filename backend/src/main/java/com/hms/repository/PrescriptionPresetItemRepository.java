package com.hms.repository;

import com.hms.entity.PrescriptionPresetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrescriptionPresetItemRepository extends JpaRepository<PrescriptionPresetItem, Long> {
    List<PrescriptionPresetItem> findByPresetIdOrderBySortOrderAsc(Long presetId);
    void deleteByPresetId(Long presetId);
}

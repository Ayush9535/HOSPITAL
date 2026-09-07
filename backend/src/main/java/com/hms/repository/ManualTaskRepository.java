package com.hms.repository;

import com.hms.entity.ManualTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ManualTaskRepository extends JpaRepository<ManualTask, Long> {
    Optional<ManualTask> findByPublicId(String publicId);
    List<ManualTask> findByAssignedToNurseUserIdAndIsActiveTrueOrderByCreatedAtDesc(Long nurseUserId);
    List<ManualTask> findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(Long hospitalId);
}

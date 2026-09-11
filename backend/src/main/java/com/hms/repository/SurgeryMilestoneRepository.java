package com.hms.repository;

import com.hms.entity.SurgeryMilestone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SurgeryMilestoneRepository extends JpaRepository<SurgeryMilestone, Long> {
    List<SurgeryMilestone> findBySurgeryIdOrderByOccurredAtAsc(Long surgeryId);
}

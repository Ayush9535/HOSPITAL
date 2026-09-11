package com.hms.repository;

import com.hms.entity.WhoChecklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WhoChecklistRepository extends JpaRepository<WhoChecklist, Long> {
    Optional<WhoChecklist> findBySurgeryId(Long surgeryId);
}

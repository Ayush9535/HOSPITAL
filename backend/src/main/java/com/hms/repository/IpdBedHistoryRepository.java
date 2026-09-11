package com.hms.repository;

import com.hms.entity.IpdBedHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IpdBedHistoryRepository extends JpaRepository<IpdBedHistory, Long> {
    List<IpdBedHistory> findByIpdAdmissionIdOrderByAssignedAtAsc(Long ipdAdmissionId);
    List<IpdBedHistory> findByIpdAdmissionIdInOrderByAssignedAtAsc(List<Long> ipdAdmissionIds);
    Optional<IpdBedHistory> findByIpdAdmissionIdAndReleasedAtIsNull(Long ipdAdmissionId);
}

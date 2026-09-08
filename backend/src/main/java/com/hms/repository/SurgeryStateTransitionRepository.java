package com.hms.repository;

import com.hms.entity.SurgeryStateTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SurgeryStateTransitionRepository extends JpaRepository<SurgeryStateTransition, Long> {

    /** A case's timeline: who moved it, when, and why. */
    List<SurgeryStateTransition> findBySurgeryIdOrderByCreatedAtAsc(Long surgeryId);

    long countBySurgeryId(Long surgeryId);

    Optional<SurgeryStateTransition> findTopBySurgeryIdAndToStatusOrderByCreatedAtDescIdDesc(Long surgeryId, String toStatus);

    /** Cancellations in a window, grouped by reason -- the NABH cancellation-rate indicator. */
    @org.springframework.data.jpa.repository.Query(value =
            "SELECT COALESCE(reason_code,'OTHER') AS reason, COUNT(*) AS n FROM surgery_state_transitions "
            + "WHERE hospital_id = :hospitalId AND to_status = 'CANCELLED' "
            + "AND created_at >= :from AND created_at < :to GROUP BY reason ORDER BY n DESC",
            nativeQuery = true)
    List<Object[]> cancellationsByReason(
            @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    /** How many cases reached a given status in a window (e.g. COMPLETED today). */
    @org.springframework.data.jpa.repository.Query(value =
            "SELECT COUNT(*) FROM surgery_state_transitions WHERE hospital_id = :hospitalId "
            + "AND to_status = :status AND created_at >= :from AND created_at < :to",
            nativeQuery = true)
    long countReaching(@org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);
}

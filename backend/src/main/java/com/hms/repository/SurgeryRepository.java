package com.hms.repository;

import com.hms.entity.Surgery;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SurgeryRepository extends JpaRepository<Surgery, Long> {
    Optional<Surgery> findByPublicId(String publicId);

    Optional<Surgery> findByPublicIdAndHospitalId(String publicId, Long hospitalId);

    /** Serializes lifecycle commands while keeping foreign IDs indistinguishable from missing ones. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Surgery s WHERE s.publicId = :publicId AND s.hospitalId = :hospitalId")
    Optional<Surgery> findByPublicIdAndHospitalIdForUpdate(
            @Param("publicId") String publicId, @Param("hospitalId") Long hospitalId);

    List<Surgery> findByHospitalIdAndStatusOrderByRequestedAtDesc(Long hospitalId, String status);

    List<Surgery> findByHospitalIdAndStatusInOrderByScheduledAtAsc(Long hospitalId, Collection<String> statuses);

    List<Surgery> findByIpdAdmissionIdOrderByRequestedAtDesc(Long ipdAdmissionId);

    List<Surgery> findByIpdAdmissionIdAndStatusIn(Long ipdAdmissionId, Collection<String> statuses);

    /** Patient-scoped: the only "already has an active surgery" check that works for day-care. */
    List<Surgery> findByPatientIdAndStatusIn(Long patientId, Collection<String> statuses);

    /** CLIN-P1: every surgery for one patient at this hospital, across every status -- the
     *  clinical timeline needs the whole history, not just the currently active case. */
    List<Surgery> findByPatientIdAndHospitalId(Long patientId, Long hospitalId);

    List<Surgery> findByOtWardIdAndStatus(Long otWardId, String status);

    /**
     * The waiting list: approved but not yet given a slot. It is a query, not a status --
     * a WAITLISTED state would exist only to be left immediately.
     */
    List<Surgery> findByHospitalIdAndStatusAndScheduledAtIsNullOrderByRequestedAtAsc(
            Long hospitalId, String status);

    /**
     * Unplanned return to OT: a completed case whose patient had an EARLIER completed case
     * within the lookback window. A NABH safety indicator; the window makes "return" precise.
     */
    @org.springframework.data.jpa.repository.Query(value =
            "SELECT COUNT(*) FROM surgeries s WHERE s.hospital_id = :hospitalId "
            + "AND s.status IN ('COMPLETED','CLOSED') AND s.completed_at >= :from AND s.completed_at < :to "
            + "AND EXISTS (SELECT 1 FROM surgeries p WHERE p.patient_id = s.patient_id AND p.id <> s.id "
            + "  AND p.status IN ('COMPLETED','CLOSED') AND p.completed_at IS NOT NULL "
            + "  AND p.completed_at < s.completed_at "
            + "  AND p.completed_at >= DATE_SUB(s.completed_at, INTERVAL :days DAY))",
            nativeQuery = true)
    long countUnplannedReturns(@org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to,
            @org.springframework.data.repository.query.Param("days") int days);

    /** Today's OT list, in theatre order. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT s FROM Surgery s WHERE s.hospitalId = :hospitalId AND s.scheduledAt >= :from "
            + "AND s.scheduledAt < :to AND s.status <> 'CANCELLED' ORDER BY s.scheduledAt ASC")
    List<Surgery> findScheduledBetween(@org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    /**
     * Does another live case occupy this theatre over [newStart, newEnd)?
     *
     * Interval overlap is `start < otherEnd AND end > otherStart`, so cases that merely
     * touch (one ends exactly as the next begins) do NOT clash. The existing case's end
     * includes the room's turnover time. A unique index cannot express this, hence the
     * query plus a pessimistic lock on the room row.
     */
    @org.springframework.data.jpa.repository.Query(value =
            "SELECT COUNT(*) FROM surgeries s WHERE s.hospital_id = :hospitalId AND s.ot_room_id = :roomId "
            + "AND s.status IN ('SCHEDULED','PRE_OP','IN_PROGRESS') AND s.id <> :excludeSurgeryId "
            + "AND s.scheduled_at IS NOT NULL AND s.scheduled_at < :newEnd "
            + "AND DATE_ADD(s.scheduled_at, INTERVAL (COALESCE(s.estimated_duration_minutes, 60) + :turnover) MINUTE) > :newStart",
            nativeQuery = true)
    long countRoomOverlaps(@org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
            @org.springframework.data.repository.query.Param("roomId") Long roomId,
            @org.springframework.data.repository.query.Param("newStart") java.time.LocalDateTime newStart,
            @org.springframework.data.repository.query.Param("newEnd") java.time.LocalDateTime newEnd,
            @org.springframework.data.repository.query.Param("turnover") int turnover,
            @org.springframework.data.repository.query.Param("excludeSurgeryId") Long excludeSurgeryId);

    /** The same surgeon cannot be operating in two theatres at once. No leave entity needed. */
    @org.springframework.data.jpa.repository.Query(value =
            "SELECT COUNT(*) FROM surgeries s WHERE s.hospital_id = :hospitalId AND s.surgeon_doctor_id = :surgeonId "
            + "AND s.status IN ('SCHEDULED','PRE_OP','IN_PROGRESS') AND s.id <> :excludeSurgeryId "
            + "AND s.scheduled_at IS NOT NULL AND s.scheduled_at < :newEnd "
            + "AND DATE_ADD(s.scheduled_at, INTERVAL COALESCE(s.estimated_duration_minutes, 60) MINUTE) > :newStart",
            nativeQuery = true)
    long countSurgeonOverlaps(@org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
            @org.springframework.data.repository.query.Param("surgeonId") Long surgeonId,
            @org.springframework.data.repository.query.Param("newStart") java.time.LocalDateTime newStart,
            @org.springframework.data.repository.query.Param("newEnd") java.time.LocalDateTime newEnd,
            @org.springframework.data.repository.query.Param("excludeSurgeryId") Long excludeSurgeryId);
}

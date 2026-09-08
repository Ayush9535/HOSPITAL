package com.hms.repository;

import com.hms.entity.OtRoomOccupancy;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OtRoomOccupancyRepository extends JpaRepository<OtRoomOccupancy, Long> {

    /** The open span for a surgery (theatre still held), if any. */
    Optional<OtRoomOccupancy> findBySurgeryIdAndOccupiedToIsNull(Long surgeryId);

    /** Closed while the theatre row is locked during strict surgery completion. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OtRoomOccupancy o WHERE o.surgeryId = :surgeryId AND o.occupiedTo IS NULL")
    Optional<OtRoomOccupancy> findOpenBySurgeryIdForUpdate(@Param("surgeryId") Long surgeryId);

    /** Closed spans in a window, room-ordered by start, for utilisation and turnover. */
    @Query("SELECT o FROM OtRoomOccupancy o WHERE o.hospitalId = :hospitalId "
            + "AND o.occupiedFrom >= :from AND o.occupiedFrom < :to AND o.occupiedTo IS NOT NULL "
            + "ORDER BY o.otRoomId ASC, o.occupiedFrom ASC")
    List<OtRoomOccupancy> findClosedSpans(@Param("hospitalId") Long hospitalId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}

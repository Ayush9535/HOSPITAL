package com.hms.repository;

import com.hms.entity.OtRoom;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OtRoomRepository extends JpaRepository<OtRoom, Long> {

    List<OtRoom> findByHospitalIdAndIsActiveTrueOrderByNameAsc(Long hospitalId);

    Optional<OtRoom> findByPublicId(String publicId);

    Optional<OtRoom> findByHospitalIdAndSourceWardId(Long hospitalId, Long sourceWardId);

    boolean existsByHospitalIdAndName(Long hospitalId, String name);

    /**
     * Serialises booking. Interval overlap cannot be expressed as a unique index, so the
     * room row is locked while we check for a clash and write. Without this, two schedulers
     * can both read "free" and both write.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM OtRoom r WHERE r.id = :id")
    Optional<OtRoom> findByIdForUpdate(@Param("id") Long id);
}

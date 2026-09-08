package com.hms.repository;

import com.hms.entity.RecoveryBay;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RecoveryBayRepository extends JpaRepository<RecoveryBay, Long> {
    List<RecoveryBay> findByHospitalIdAndIsActiveTrueOrderByNameAsc(Long hospitalId);

    Optional<RecoveryBay> findByPublicIdAndHospitalId(String publicId, Long hospitalId);

    /** Tenant-scoped by-id lookup for display (e.g. the board's bay name), so a raw id never
     *  needs a separate "trust the caller" review. */
    Optional<RecoveryBay> findByIdAndHospitalId(Long id, Long hospitalId);

    boolean existsByHospitalIdAndName(Long hospitalId, String name);

    /** Locks the bay row before checking/claiming occupancy, so two concurrent admissions to the
     *  same bay cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM RecoveryBay b WHERE b.id = :id AND b.hospitalId = :hospitalId")
    Optional<RecoveryBay> findByIdAndHospitalIdForUpdate(@Param("id") Long id, @Param("hospitalId") Long hospitalId);
}

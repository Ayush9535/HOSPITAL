package com.hms.repository;

import com.hms.entity.Bed;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface BedRepository extends JpaRepository<Bed, Long> {
    @org.springframework.data.jpa.repository.Query(
            "SELECT b.bedId FROM Bed b WHERE b.wardId = :wardId AND b.hospitalId = :hospitalId "
          + "AND LOWER(b.status) = 'available' ORDER BY b.bedId")
    List<Long> findAvailableBedIdsInWard(
            @org.springframework.data.repository.query.Param("wardId") Long wardId,
            @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId);


    List<Bed> findByWardIdAndHospitalId(Long wardId, Long hospitalId);
    List<Bed> findByHospitalIdAndStatus(Long hospitalId, String status);
    List<Bed> findByHospitalId(Long hospitalId);

    /**
     * Tenant-scoped lookup by id. A bed id arrives from the client on admission and bed
     * transfer, so resolving it with a bare findById let one hospital name another's bed:
     * the admission row was written before the tenant-scoped BedStatusService refused, and
     * with a foreign ward it was not refused at all.
     */
    java.util.Optional<Bed> findByBedIdAndHospitalId(Long bedId, Long hospitalId);

    /**
     * ICU Phase 2 — every bed of a set of wards, in one tenant-scoped query. The existing code
     * fetches ward by ward (or fetches the hospital's whole bed list and filters in Java); the
     * ICU board reads N units per request, so it asks once.
     */
    List<Bed> findByHospitalIdAndWardIdIn(Long hospitalId, java.util.Collection<Long> wardIds);

    /**
     * E1 (C3) — serialises a bed claim. Mirrors {@code OtRoomRepository.findByIdForUpdate}.
     *
     * <p>Bed availability is a mutable row, so "read available, then claim" races: two callers
     * both see AVAILABLE and both write OCCUPIED, and the second silently overwrites
     * {@code current_ipd_admission_id}. Unlike an IPD number there is no unique index that could
     * catch it, so the row is locked while we re-check and write.
     *
     * <p>Tenant-scoped in the same query (C4): a bed id arrives from the client on both admission
     * and transfer, so resolving it must never reach another hospital's row. An explicit
     * {@code @Query} is required for the lock hint to apply — a derived finder would not carry it.
     *
     * <p>The lock is held until the surrounding transaction commits, so this MUST be called from
     * inside one. Outside a transaction it is released immediately and protects nothing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Bed b WHERE b.bedId = :bedId AND b.hospitalId = :hospitalId")
    java.util.Optional<Bed> findByBedIdAndHospitalIdForUpdate(
            @Param("bedId") Long bedId, @Param("hospitalId") Long hospitalId);

    /**
     * Every bed of the hospital, counted by status, in one statement.
     *
     * <p>Grouping in SQL rather than loading the beds and counting in Java: the occupancy card
     * needs five numbers, not the ward's furniture. Statuses are free text at the column level, so
     * the caller must treat anything outside the four known values as unknown rather than assume.
     */
    @Query("SELECT b.status, COUNT(b) FROM Bed b WHERE b.hospitalId = :hospitalId GROUP BY b.status")
    java.util.List<Object[]> countByStatusForHospital(@Param("hospitalId") Long hospitalId);
}

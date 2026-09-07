package com.hms.repository;

import com.hms.entity.IcuIoEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** ICU Phase 5. Every finder is tenant-scoped; nothing here resolves a bare id. */
public interface IcuIoEntryRepository extends JpaRepository<IcuIoEntry, Long> {

    Optional<IcuIoEntry> findByPublicIdAndHospitalId(String publicId, Long hospitalId);

    List<IcuIoEntry> findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueOrderByOccurredAtDesc(
            Long ipdAdmissionId, Long hospitalId);

    List<IcuIoEntry> findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueAndOccurredAtBetweenOrderByOccurredAtDesc(
            Long ipdAdmissionId, Long hospitalId, LocalDateTime from, LocalDateTime to);

    /**
     * Ids that a later correction superseded, so a balance can exclude them.
     *
     * <p>The superseded row is deliberately still readable (append-only, per ICU-4); it just must
     * not be counted twice alongside the correction that replaced it.
     */
    @Query("SELECT e.supersedesIoEntryId FROM IcuIoEntry e "
         + "WHERE e.ipdAdmissionId = :admissionId AND e.hospitalId = :hospitalId "
         + "AND e.supersedesIoEntryId IS NOT NULL")
    List<Long> findSupersededIds(@Param("admissionId") Long admissionId,
                                 @Param("hospitalId") Long hospitalId);
}

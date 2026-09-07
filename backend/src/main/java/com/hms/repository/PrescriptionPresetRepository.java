package com.hms.repository;

import com.hms.entity.PrescriptionPreset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrescriptionPresetRepository extends JpaRepository<PrescriptionPreset, Long> {
    // Admin view: every preset in the hospital (shared + all doctors' private ones).
    List<PrescriptionPreset> findByHospitalIdAndIsActiveTrueOrderByDisplayOrderAsc(Long hospitalId);

    // Doctor view: shared presets (doctor_id IS NULL) plus the doctor's own private ones.
    @Query("SELECT p FROM PrescriptionPreset p WHERE p.hospitalId = :hospitalId AND p.isActive = true " +
           "AND (p.doctorId IS NULL OR p.doctorId = :doctorId) ORDER BY p.displayOrder ASC")
    List<PrescriptionPreset> findVisibleToDoctor(@Param("hospitalId") Long hospitalId, @Param("doctorId") Long doctorId);

    // The same two views, scoped to one preset engine (PRESCRIPTION / IN_CLINIC), so the two
    // lists share a table without ever bleeding into each other.
    List<PrescriptionPreset> findByHospitalIdAndPresetTypeAndIsActiveTrueOrderByDisplayOrderAsc(
            Long hospitalId, String presetType);

    @Query("SELECT p FROM PrescriptionPreset p WHERE p.hospitalId = :hospitalId AND p.isActive = true " +
           "AND p.presetType = :presetType AND (p.doctorId IS NULL OR p.doctorId = :doctorId) " +
           "ORDER BY p.displayOrder ASC")
    List<PrescriptionPreset> findVisibleToDoctorByType(@Param("hospitalId") Long hospitalId,
                                                       @Param("doctorId") Long doctorId,
                                                       @Param("presetType") String presetType);

    Optional<PrescriptionPreset> findByIdAndHospitalId(Long id, Long hospitalId);
    Optional<PrescriptionPreset> findByIdAndHospitalIdAndDoctorId(Long id, Long hospitalId, Long doctorId);
}

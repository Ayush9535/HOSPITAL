package com.hms.repository;

import com.hms.entity.ConsultationNotePreset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConsultationNotePresetRepository extends JpaRepository<ConsultationNotePreset, Long> {
    // Admin view: every preset in the hospital (shared + all doctors' private ones).
    List<ConsultationNotePreset> findByHospitalIdAndFieldTypeAndIsActiveTrueOrderByDisplayOrderAsc(Long hospitalId, String fieldType);

    // Doctor view: shared presets (doctor_id IS NULL) plus the doctor's own private ones.
    @Query("SELECT p FROM ConsultationNotePreset p WHERE p.hospitalId = :hospitalId AND p.fieldType = :fieldType " +
           "AND p.isActive = true AND (p.doctorId IS NULL OR p.doctorId = :doctorId) ORDER BY p.displayOrder ASC")
    List<ConsultationNotePreset> findVisibleToDoctor(@Param("hospitalId") Long hospitalId,
                                                     @Param("fieldType") String fieldType,
                                                     @Param("doctorId") Long doctorId);

    Optional<ConsultationNotePreset> findByIdAndHospitalId(Long id, Long hospitalId);
    Optional<ConsultationNotePreset> findByIdAndHospitalIdAndDoctorId(Long id, Long hospitalId, Long doctorId);
}

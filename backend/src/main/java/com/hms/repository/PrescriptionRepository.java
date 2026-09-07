package com.hms.repository;

import com.hms.entity.Prescription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {
    java.util.Optional<Prescription> findByIdAndHospitalId(Long id, Long hospitalId);
    List<Prescription> findByMedicalRecordId(Long medicalRecordId);

    List<Prescription> findByMedicalRecordIdIn(List<Long> medicalRecordIds);

    List<Prescription> findByHospitalIdAndStatus(Long hospitalId, String status);

    // For Pharmacy Dashboard
    // For Pharmacy Dashboard
    // List<Prescription> findByHospitalIdAndStatus(Long hospitalId, String status);

    @Query("SELECT p FROM Prescription p JOIN MedicalRecord m ON p.medicalRecordId = m.id WHERE m.ipdAdmissionId = :ipdId AND p.status = :status ORDER BY p.startDate")
    List<Prescription> findByIpdAdmissionIdAndStatus(Long ipdId, String status);

    @Query("SELECT p FROM Prescription p JOIN MedicalRecord m ON p.medicalRecordId = m.id WHERE m.ipdAdmissionId = :ipdId ORDER BY p.startDate")
    List<Prescription> findByIpdAdmissionIdOrderByStartDate(Long ipdId);
}

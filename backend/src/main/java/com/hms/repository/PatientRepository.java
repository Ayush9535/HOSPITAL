package com.hms.repository;

import com.hms.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

/**
 * PatientRepository - Data access layer for Patient entity
 * 
 * This repository provides database operations for managing patients.
 * All queries automatically filter by hospital_id for multi-tenant isolation.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Repository
public interface PatientRepository extends JpaRepository<Patient, Long> {

        long countByHospitalId(Long hospitalId);

        long countByHospitalIdAndIsActiveTrue(Long hospitalId);

        /**
         * Find all patients belonging to a specific hospital
         * Used to list patients for a hospital (multi-tenant filtering)
         * 
         * @param hospitalId Hospital ID to filter by
         * @return List of patients for the hospital
         */
        /**
         * Find all active patients belonging to a specific hospital
         * Used to list patients for a hospital (multi-tenant filtering)
         * 
         * @param hospitalId Hospital ID to filter by
         * @return List of active patients for the hospital
         */
        org.springframework.data.domain.Page<Patient> findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(
                        Long hospitalId,
                        org.springframework.data.domain.Pageable pageable);

        List<Patient> findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(Long hospitalId);

        List<Patient> findByHospitalIdAndIsActiveTrue(Long hospitalId);

        /**
         * Find an active patient by ID and hospital ID
         * Ensures multi-tenant isolation - patient must belong to the hospital
         * 
         * @param id         Patient ID
         * @param hospitalId Hospital ID to filter by
         * @return Optional containing the patient if found, active, and belongs to the
         *         hospital
         */
        Optional<Patient> findByIdAndHospitalIdAndIsActiveTrue(Long id, Long hospitalId);

        /** Serializes admission decisions for one tenant-scoped patient. */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT p FROM Patient p WHERE p.id = :id AND p.hospitalId = :hospitalId")
        Optional<Patient> findByIdAndHospitalIdForUpdate(@Param("id") Long id,
                        @Param("hospitalId") Long hospitalId);
        /**
         * ICU Phase 2 — batch, tenant-scoped resolve for a board that shows many patients at
         * once. Scoped rather than a bare findAllById so a stray id can never cross tenants.
         */
        List<Patient> findByHospitalIdAndIdIn(Long hospitalId, java.util.Collection<Long> ids);

        Optional<Patient> findByPublicIdAndHospitalIdAndIsActiveTrue(String publicId, Long hospitalId);

        /**
         * Search active patients by name or phone
         * 
         * <p>Written out rather than derived from a method name: the derived form emitted a LIKE
         * ESCAPE clause that H2 rejects outright, so every search answered 409. Spelling the
         * predicate out also keeps the tenant filter in one place instead of relying on the reader
         * to work out AND/OR precedence across a 100-character method name.
         *
         * @param hospitalId Hospital ID
         * @param term       Name or phone search term
         * @return List of matching patients
         */
        @Query("SELECT p FROM Patient p WHERE p.hospitalId = :hospitalId AND p.isActive = true "
                        + "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :term, '%')) "
                        + "OR p.phone LIKE CONCAT('%', :term, '%')) ORDER BY p.createdAt DESC")
        List<Patient> searchActiveByNameOrPhone(@Param("hospitalId") Long hospitalId,
                        @Param("term") String term);

        /**
         * Find patient by phone number and hospital ID
         * Used to check if patient already exists before creating new one
         * 
         * @param phone      Phone number
         * @param hospitalId Hospital ID
         * @return Optional containing the patient if found
         */
        List<Patient> findByPhoneAndHospitalIdAndIsActiveTrue(String phone, Long hospitalId);

        /**
         * Find active patients created within a date range (for Today filter)
         */
        Optional<Patient> findByPublicId(String publicId);

        /** Business wall-clock interval [from, toExclusive). */
        @Query("SELECT p FROM Patient p WHERE p.hospitalId = :hospitalId AND p.isActive = true "
                        + "AND p.createdAt >= :from AND p.createdAt < :toExclusive ORDER BY p.createdAt DESC")
        org.springframework.data.domain.Page<Patient> findActiveInDateRange(
                        @Param("hospitalId") Long hospitalId, @Param("from") java.time.LocalDateTime from,
                        @Param("toExclusive") java.time.LocalDateTime toExclusive,
                        org.springframework.data.domain.Pageable pageable);

        /**
         * Count active patients created within a date range
         * Used for "Patients This Month" and "Patients Today" stats
         */
        @Query("SELECT COUNT(p) FROM Patient p WHERE p.hospitalId = :hospitalId AND p.isActive = true "
                        + "AND p.createdAt >= :from AND p.createdAt < :toExclusive")
        long countActiveInDateRange(@Param("hospitalId") Long hospitalId,
                        @Param("from") java.time.LocalDateTime from,
                        @Param("toExclusive") java.time.LocalDateTime toExclusive);
}

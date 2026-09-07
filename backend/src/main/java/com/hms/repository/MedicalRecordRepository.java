package com.hms.repository;

import com.hms.entity.MedicalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MedicalRecordRepository extends JpaRepository<MedicalRecord, Long> {
    List<MedicalRecord> findByPatientId(Long patientId);
    List<MedicalRecord> findByPatientIdOrderByCreatedAtDesc(Long patientId);

    Optional<MedicalRecord> findByAppointmentId(Long appointmentId);

    Optional<MedicalRecord> findByOpdId(Long opdId);

    List<MedicalRecord> findTop5ByPatientIdOrderByCreatedAtDesc(Long patientId);

    Optional<MedicalRecord> findTopByPatientIdOrderByCreatedAtDesc(Long patientId);

    List<MedicalRecord> findByIpdAdmissionIdOrderByCreatedAtDesc(Long ipdAdmissionId);
    List<MedicalRecord> findByIpdAdmissionIdOrderByCreatedAtAsc(Long ipdAdmissionId);
    List<MedicalRecord> findByIpdAdmissionIdIn(List<Long> ipdAdmissionIds);

    @org.springframework.data.jpa.repository.Query("SELECT r FROM MedicalRecord r WHERE r.id = :id AND r.hospitalId = :hospitalId")
    java.util.Optional<MedicalRecord> findByIdAndHospitalId(
            @org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId);


/**
     * Outstanding follow-ups for one facility in a date window, as the list the front desk reads.
     *
     * <p>Patient and doctor are joined here rather than fetched per row: the due list is opened
     * repeatedly through the day and one query per patient would make it quadratic in the number
     * of people expected back.
     *
     * <p>A NULL status counts as open. Every consultation recorded before the column existed has
     * one, and treating those as closed would silently retire real follow-ups that nobody has
     * acted on.
     *
     * <p>hospitalId is matched on medical_records directly — the table carries a NOT NULL
     * hospital_id — and again on the patient, so a record pointing at another facility's patient
     * cannot drag that patient's name into this list.
     */
/**
     * Claims a follow-up for exactly one arrival, atomically.
     *
     * <p>Returns 1 to the caller that won and 0 to every other. The WHERE clause is the whole
     * mechanism: the database serialises these updates, so unlike a read-then-write check there
     * is no window between deciding the follow-up is unclaimed and claiming it. Same shape as
     * MedicineStockBatchRepository.deductAtomically, for the same reason.
     *
     * <p>Only an open, unclaimed follow-up in the caller's own facility can be taken — a
     * terminal or already-actioned one matches nothing and the caller is told it lost.
     */
/**
     * Moves an open follow-up to a new date, atomically.
     *
     * <p>Same guard as the arrival claim, for the same reason: a reschedule racing an arrival
     * must not move a date out from under a visit that is being created, and two reschedules
     * must not interleave. Returns 1 to the winner, 0 to anyone whose follow-up was already
     * actioned, completed or cancelled.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MedicalRecord m SET m.followUpDate = :newDate, "
         + "m.followUpInstructions = COALESCE(:instructions, m.followUpInstructions) "
         + "WHERE m.id = :id AND m.hospitalId = :hospitalId "
         + "AND m.actionedOpdId IS NULL "
         + "AND (m.followUpStatus IS NULL OR m.followUpStatus = 'OPEN')")
    int rescheduleIfOpen(@org.springframework.data.repository.query.Param("id") Long id,
                         @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
                         @org.springframework.data.repository.query.Param("newDate") java.time.LocalDate newDate,
                         @org.springframework.data.repository.query.Param("instructions") String instructions);

    /**
     * Closes an open follow-up without a visit — completed or cancelled.
     *
     * <p>The date and instructions are untouched: the obligation ends, the clinical record of
     * what was asked for does not.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MedicalRecord m SET m.followUpStatus = :status "
         + "WHERE m.id = :id AND m.hospitalId = :hospitalId "
         + "AND m.actionedOpdId IS NULL "
         + "AND (m.followUpStatus IS NULL OR m.followUpStatus = 'OPEN')")
    int closeIfOpen(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
                    @org.springframework.data.repository.query.Param("status") String status);

        @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MedicalRecord m SET m.followUpStatus = 'ACTIONED', m.actionedOpdId = :opdId, "
         + "m.actionedByUserId = :userId, m.actionedAt = :at "
         + "WHERE m.id = :id AND m.hospitalId = :hospitalId "
         + "AND m.actionedOpdId IS NULL "
         + "AND m.followUpDate IS NOT NULL AND m.followUpDate <= :today "
         + "AND (m.followUpStatus IS NULL OR m.followUpStatus = 'OPEN')")
    int claimForArrival(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
                        @org.springframework.data.repository.query.Param("opdId") Long opdId,
                        @org.springframework.data.repository.query.Param("userId") Long userId,
                        @org.springframework.data.repository.query.Param("at") java.time.LocalDateTime at,
                        @org.springframework.data.repository.query.Param("today") java.time.LocalDate today);

        @Query("SELECT new com.hms.dto.FollowUpDTO("
         + "  m.id, m.opdId, p.id, p.publicId, p.customId, p.name, p.phone,"
         + "  d.id, d.name, m.followUpDate, m.followUpInstructions, m.diagnosis, m.followUpStatus) "
         + "FROM MedicalRecord m "
         + "JOIN Patient p ON p.id = m.patientId AND p.hospitalId = m.hospitalId "
         + "LEFT JOIN Doctor d ON d.id = m.doctorId AND d.hospitalId = m.hospitalId "
         + "WHERE m.hospitalId = :hospitalId "
         + "AND m.followUpDate IS NOT NULL "
         + "AND (m.followUpStatus IS NULL OR m.followUpStatus = 'OPEN') "
         + "AND m.followUpDate >= :from AND m.followUpDate <= :to "
         + "ORDER BY m.followUpDate ASC, p.name ASC")
    List<com.hms.dto.FollowUpDTO> findOpenFollowUpsBetween(
            @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDate to);

    /** Same list narrowed to one doctor's own patients. */
    @Query("SELECT new com.hms.dto.FollowUpDTO("
         + "  m.id, m.opdId, p.id, p.publicId, p.customId, p.name, p.phone,"
         + "  d.id, d.name, m.followUpDate, m.followUpInstructions, m.diagnosis, m.followUpStatus) "
         + "FROM MedicalRecord m "
         + "JOIN Patient p ON p.id = m.patientId AND p.hospitalId = m.hospitalId "
         + "LEFT JOIN Doctor d ON d.id = m.doctorId AND d.hospitalId = m.hospitalId "
         + "WHERE m.hospitalId = :hospitalId AND m.doctorId = :doctorId "
         + "AND m.followUpDate IS NOT NULL "
         + "AND (m.followUpStatus IS NULL OR m.followUpStatus = 'OPEN') "
         + "AND m.followUpDate >= :from AND m.followUpDate <= :to "
         + "ORDER BY m.followUpDate ASC, p.name ASC")
    List<com.hms.dto.FollowUpDTO> findOpenFollowUpsBetweenForDoctor(
            @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
            @org.springframework.data.repository.query.Param("doctorId") Long doctorId,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDate to);

    /**
     * OPD consultations in a business-day window: encounters, not distinct patients.
     *
     * <p>{@code visit_type = 'OPD'} is what separates a consultation from an inpatient ward round.
     * IpdAdmissionService writes its own records with visit_type 'IPD' and its comment is explicit
     * that those carry no consultation fee — a doctor visiting their own admitted patient is not a
     * new consultation. The column is NOT NULL, so no legacy row can slip through untyped.
     */
    @Query("SELECT COUNT(m) FROM MedicalRecord m WHERE m.hospitalId = :hospitalId "
            + "AND m.visitType = 'OPD' AND m.createdAt >= :from AND m.createdAt < :toExclusive")
    long countOpdConsultationsInRange(
            @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("toExclusive") java.time.LocalDateTime toExclusive);
}

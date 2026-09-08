package com.hms.repository;

import com.hms.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * AppointmentRepository - Data access layer for Appointment entity
 * 
 * This repository provides database operations for managing appointments.
 * All queries automatically filter by hospital_id for multi-tenant isolation.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

  /**
   * Find all active appointments belonging to a specific hospital
   * Used to list appointments for a hospital (multi-tenant filtering)
   * 
   * @param hospitalId Hospital ID to filter by
   * @return List of active appointments for the hospital
   */
  org.springframework.data.domain.Page<Appointment> findByHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(
      Long hospitalId, org.springframework.data.domain.Pageable pageable);

  List<Appointment> findByHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(Long hospitalId);

  /**
   * Find all active appointments for a specific doctor in a hospital
   * Used by doctors to view their own appointments
   * 
   * @param doctorId   Doctor ID
   * @param hospitalId Hospital ID to filter by
   * @return List of active appointments for the doctor
   */
  List<Appointment> findByDoctorIdAndHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(Long doctorId,
      Long hospitalId);

  /**
   * Find an active appointment by ID and hospital ID
   * Ensures multi-tenant isolation - appointment must belong to the hospital
   * 
   * @param id         Appointment ID
   * @param hospitalId Hospital ID to filter by
   * @return Optional containing the appointment if found, active, and belongs to
   *         the hospital
   */
  Optional<Appointment> findByIdAndHospitalIdAndIsActiveTrue(Long id, Long hospitalId);

  Optional<Appointment> findByPublicIdAndHospitalIdAndIsActiveTrue(String publicId, Long hospitalId);

  /**
   * Count active appointments for a specific date
   */
  long countByHospitalIdAndIsActiveTrueAndAppointmentDate(Long hospitalId, java.time.LocalDate appointmentDate);

  /**
   * Count active appointments by status
   */
  long countByHospitalIdAndIsActiveTrueAndStatus(Long hospitalId, String status);

  /**
   * Count all active appointments
   */
  long countByHospitalIdAndIsActiveTrue(Long hospitalId);

  /**
   * Find appointments by hospital and date for Dashboard
   * Used to get today's appointments for the Overview dashboard
   * 
   * @param hospitalId      Hospital ID
   * @param appointmentDate Date to filter by
   * @return List of appointments for the specified date
   */
  List<Appointment> findByHospitalIdAndAppointmentDateAndIsActiveTrue(Long hospitalId,
      java.time.LocalDate appointmentDate);

  // ---------------------------------------------------------------
  // Filter Queries for Doctor/Receptionist View
  // ---------------------------------------------------------------

  /**
   * TODAY: Active appointments for a specific date (Today)
   * Sorted by Time ASC
   */
  List<Appointment> findByDoctorIdAndAppointmentDateAndIsActiveTrueOrderByAppointmentTimeAsc(Long doctorId,
      java.time.LocalDate appointmentDate);

  /**
   * UPCOMING: Active appointments AFTER a specific date
   * Sorted by Date ASC, then Time ASC
   */
  List<Appointment> findByDoctorIdAndAppointmentDateGreaterThanEqualAndIsActiveTrueOrderByAppointmentDateAscAppointmentTimeAsc(
      Long doctorId, java.time.LocalDate appointmentDate);

  /**
   * HISTORY: Appointments BEFORE a specific date OR with status
   * COMPLETED/CANCELLED
   * Sorted by Date DESC, then Time DESC
   */
  List<Appointment> findByDoctorIdAndIsActiveTrueAndAppointmentDateBeforeOrDoctorIdAndIsActiveTrueAndStatusInOrderByAppointmentDateDescAppointmentTimeDesc(
      Long doctorId1, java.time.LocalDate date, Long doctorId2, List<String> statuses);

  // ---------------------------------------------------------------
  // Filter Queries for Hospital Admin/Receptionist View (Paginated)
  // ---------------------------------------------------------------

  /**
   * TODAY: Active appointments for a specific date (Today)
   * Sorted by Time ASC
   */
  org.springframework.data.domain.Page<Appointment> findByHospitalIdAndAppointmentDateAndIsActiveTrueOrderByAppointmentTimeAsc(
      Long hospitalId, java.time.LocalDate appointmentDate,
      org.springframework.data.domain.Pageable pageable);

  /**
   * UPCOMING: Active appointments AFTER a specific date
   * Sorted by Date ASC, then Time ASC
   */
  /**
   * UPCOMING is from today onwards, inclusive.
   *
   * <p>It used to be strictly after today, which quietly dropped every patient due later the
   * same day: a booking taken this morning for this afternoon appeared under Today and under
   * nothing else, so a doctor who had switched to Upcoming saw an empty list and concluded the
   * appointment had never been made. Overlapping with Today is the correct behaviour -- the two
   * views answer different questions ("what is my day" and "what is still to come") and a
   * patient can legitimately belong to both.
   */
  org.springframework.data.domain.Page<Appointment> findByHospitalIdAndAppointmentDateGreaterThanEqualAndIsActiveTrueOrderByAppointmentDateAscAppointmentTimeAsc(
      Long hospitalId, java.time.LocalDate appointmentDate,
      org.springframework.data.domain.Pageable pageable);

  /**
   * HISTORY: Appointments BEFORE a specific date OR with status
   * COMPLETED/CANCELLED
   * Sorted by Date DESC, then Time DESC
   */
  org.springframework.data.domain.Page<Appointment> findByHospitalIdAndIsActiveTrueAndAppointmentDateBeforeOrHospitalIdAndIsActiveTrueAndStatusInOrderByAppointmentDateDescAppointmentTimeDesc(
      Long hospitalId1, java.time.LocalDate date, Long hospitalId2, List<String> statuses,
      org.springframework.data.domain.Pageable pageable);

  /**
   * Find active appointments for a doctor on a specific date
   * Used for slot validation
   */
  List<Appointment> findByDoctorIdAndAppointmentDateAndIsActiveTrue(Long doctorId,
      java.time.LocalDate appointmentDate);

  /**
   * Find active appointments for a specific patient
   * Used for Patient History
   */
  List<Appointment> findByPatientIdAndHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(Long patientId,
      Long hospitalId);

  // ...existing code...

  @Query("""
          SELECT a FROM Appointment a
          JOIN Patient p ON a.patientId = p.id
          JOIN Doctor d ON a.doctorId = d.id
          WHERE a.hospitalId = :hospitalId
            AND a.isActive = true
            AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(d.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(a.customId) LIKE LOWER(CONCAT('%', :search, '%')))
          ORDER BY a.appointmentDate DESC, a.appointmentTime DESC
      """)
  org.springframework.data.domain.Page<Appointment> searchAppointments(Long hospitalId, String search,
      org.springframework.data.domain.Pageable pageable);

  @Query("""
          SELECT a FROM Appointment a
          JOIN Patient p ON a.patientId = p.id
          JOIN Doctor d ON a.doctorId = d.id
          WHERE a.hospitalId = :hospitalId
            AND a.isActive = true
            AND a.appointmentDate = :date
            AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(d.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(a.customId) LIKE LOWER(CONCAT('%', :search, '%')))
          ORDER BY a.appointmentTime ASC
      """)
  org.springframework.data.domain.Page<Appointment> searchAppointmentsByDate(Long hospitalId, String search,
      LocalDate date, org.springframework.data.domain.Pageable pageable);

  @Query("""
          SELECT a FROM Appointment a
          JOIN Patient p ON a.patientId = p.id
          JOIN Doctor d ON a.doctorId = d.id
          WHERE a.hospitalId = :hospitalId
            AND a.isActive = true
            AND a.appointmentDate >= :date
            AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(d.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(a.customId) LIKE LOWER(CONCAT('%', :search, '%')))
          ORDER BY a.appointmentDate ASC, a.appointmentTime ASC
      """)
  org.springframework.data.domain.Page<Appointment> searchAppointmentsByDateAfter(Long hospitalId, String search,
      LocalDate date, org.springframework.data.domain.Pageable pageable);

  @Query("""
          SELECT a FROM Appointment a
          JOIN Patient p ON a.patientId = p.id
          JOIN Doctor d ON a.doctorId = d.id
          WHERE a.hospitalId = :hospitalId
            AND a.isActive = true
            AND (a.appointmentDate < :date OR a.status IN ('COMPLETED', 'CANCELLED'))
            AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(d.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(a.customId) LIKE LOWER(CONCAT('%', :search, '%')))
          ORDER BY a.appointmentDate DESC, a.appointmentTime DESC
      """)
  org.springframework.data.domain.Page<Appointment> searchAppointmentsHistory(Long hospitalId, String search,
      LocalDate date, org.springframework.data.domain.Pageable pageable);

  // ...existing code...

  // ...existing code...

  /**
   * Search appointments by doctor with pagination
   */
  @Query("""
          SELECT a FROM Appointment a
          JOIN Patient p ON a.patientId = p.id
          JOIN Doctor d ON a.doctorId = d.id
          WHERE a.doctorId = :doctorId
            AND a.hospitalId = :hospitalId
            AND a.isActive = true
            AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(d.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(a.customId) LIKE LOWER(CONCAT('%', :search, '%')))
          ORDER BY a.appointmentDate DESC, a.appointmentTime DESC
      """)
  org.springframework.data.domain.Page<Appointment> searchAppointmentsByDoctor(Long doctorId, Long hospitalId,
      String search, org.springframework.data.domain.Pageable pageable);

  /**
   * Search TODAY appointments by doctor with pagination
   */
  @Query("""
          SELECT a FROM Appointment a
          JOIN Patient p ON a.patientId = p.id
          JOIN Doctor d ON a.doctorId = d.id
          WHERE a.doctorId = :doctorId
            AND a.hospitalId = :hospitalId
            AND a.isActive = true
            AND a.appointmentDate = :date
            AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(d.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(a.customId) LIKE LOWER(CONCAT('%', :search, '%')))
          ORDER BY a.appointmentTime ASC
      """)
  org.springframework.data.domain.Page<Appointment> searchAppointmentsByDoctorAndDate(Long doctorId,
      Long hospitalId, String search, LocalDate date, org.springframework.data.domain.Pageable pageable);

  /**
   * Search UPCOMING appointments by doctor with pagination
   */
  @Query("""
          SELECT a FROM Appointment a
          JOIN Patient p ON a.patientId = p.id
          JOIN Doctor d ON a.doctorId = d.id
          WHERE a.doctorId = :doctorId
            AND a.hospitalId = :hospitalId
            AND a.isActive = true
            AND a.appointmentDate >= :date
            AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(d.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(a.customId) LIKE LOWER(CONCAT('%', :search, '%')))
          ORDER BY a.appointmentDate ASC, a.appointmentTime ASC
      """)
  org.springframework.data.domain.Page<Appointment> searchAppointmentsByDoctorAndDateAfter(Long doctorId,
      Long hospitalId, String search, LocalDate date, org.springframework.data.domain.Pageable pageable);

  /**
   * Search HISTORY appointments by doctor with pagination
   */
  @Query("""
          SELECT a FROM Appointment a
          JOIN Patient p ON a.patientId = p.id
          JOIN Doctor d ON a.doctorId = d.id
          WHERE a.doctorId = :doctorId
            AND a.hospitalId = :hospitalId
            AND a.isActive = true
            AND (a.appointmentDate < :date OR a.status IN ('COMPLETED', 'CANCELLED'))
            AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(d.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(a.customId) LIKE LOWER(CONCAT('%', :search, '%')))
          ORDER BY a.appointmentDate DESC, a.appointmentTime DESC
      """)
  org.springframework.data.domain.Page<Appointment> searchAppointmentsHistoryByDoctor(Long doctorId,
      Long hospitalId, String search, LocalDate date, org.springframework.data.domain.Pageable pageable);

  /**
   * Find paginated TODAY appointments by doctor
   */
  org.springframework.data.domain.Page<Appointment> findByDoctorIdAndAppointmentDateAndIsActiveTrueOrderByAppointmentTimeAsc(
      Long doctorId, java.time.LocalDate appointmentDate,
      org.springframework.data.domain.Pageable pageable);

  /**
   * Find paginated UPCOMING appointments by doctor
   */
  org.springframework.data.domain.Page<Appointment> findByDoctorIdAndAppointmentDateGreaterThanEqualAndIsActiveTrueOrderByAppointmentDateAscAppointmentTimeAsc(
      Long doctorId, java.time.LocalDate appointmentDate,
      org.springframework.data.domain.Pageable pageable);

  /**
   * Find paginated HISTORY appointments by doctor
   */
  org.springframework.data.domain.Page<Appointment> findByDoctorIdAndIsActiveTrueAndAppointmentDateBeforeOrDoctorIdAndIsActiveTrueAndStatusInOrderByAppointmentDateDescAppointmentTimeDesc(
      Long doctorId1, java.time.LocalDate date, Long doctorId2, List<String> statuses,
      org.springframework.data.domain.Pageable pageable);

  /**
   * Find all paginated active appointments by doctor and hospital
   */
  org.springframework.data.domain.Page<Appointment> findByDoctorIdAndHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(
      Long doctorId, Long hospitalId, org.springframework.data.domain.Pageable pageable);

  /**
   * Find active appointments by hospital, date, and status
   * Used by AppointmentReminderScheduler to get tomorrow's scheduled appointments
   *
   * @param hospitalId Hospital ID
   * @param date       Appointment date
   * @param status     Appointment status (e.g. "SCHEDULED")
   * @return List of matching active appointments
   */
  List<Appointment> findByHospitalIdAndAppointmentDateAndStatusAndIsActiveTrue(
      Long hospitalId, java.time.LocalDate date, String status);

  // ...existing code...
}

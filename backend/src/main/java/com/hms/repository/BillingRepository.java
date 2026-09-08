package com.hms.repository;

import com.hms.entity.Billing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillingRepository extends JpaRepository<Billing, Long> {

    /**
     * Tenant-scoped, row-locked read of one bill.
     *
     * <p>Used by the payment path so that reading the collected total and inserting the new
     * payment happen under a lock on the bill. Without it two concurrent /pay calls -- a
     * double-clicked "Paid" button, or a client retry after a timeout -- both read the same
     * "already paid" figure, both conclude the amount fits inside the outstanding balance, and
     * both insert. The patient is then charged twice.
     *
     * <p>hospital_id is part of the predicate so a foreign bill is not merely rejected later but
     * never loaded at all.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Billing b WHERE b.id = :id AND b.hospitalId = :hospitalId")
    java.util.Optional<Billing> findByIdAndHospitalIdForUpdate(
            @org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId);
    Page<Billing> findByHospitalId(Long hospitalId, Pageable pageable);

    List<Billing> findByHospitalId(Long hospitalId);

    Page<Billing> findByHospitalIdAndPaymentStatus(Long hospitalId, String paymentStatus, Pageable pageable);

    @Query("""
                SELECT b FROM Billing b
                JOIN Patient p ON b.patientId = p.id
                WHERE b.hospitalId = :hospitalId
                  AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
                       OR LOWER(b.customId) LIKE LOWER(CONCAT('%', :search, '%')))
                ORDER BY b.createdAt DESC
            """)
    Page<Billing> searchBillings(Long hospitalId, String search, Pageable pageable);

    java.util.Optional<Billing> findTopByPatientIdOrderByCreatedAtDesc(Long patientId);

    @Query("""
        SELECT b FROM Billing b
        WHERE b.id IN (
            SELECT MAX(b2.id)
            FROM Billing b2
            WHERE b2.patientId IN :patientIds
            GROUP BY b2.patientId
        )
    """)
    List<Billing> findLatestBillForPatients(@org.springframework.data.repository.query.Param("patientIds") List<Long> patientIds);

    List<Billing> findByPatientIdOrderByCreatedAtDesc(Long patientId);

    java.util.List<Billing> findByIpdAdmissionId(Long ipdAdmissionId);

    boolean existsByAppointmentId(Long appointmentId);

    boolean existsByOpdId(Long opdId);

    java.util.Optional<Billing> findByAppointmentId(Long appointmentId);

    java.util.Optional<Billing> findByOpdId(Long opdId);

    java.util.List<Billing> findByHospitalIdAndCreatedAtAfter(Long hospitalId, java.time.LocalDateTime createdAt);
}

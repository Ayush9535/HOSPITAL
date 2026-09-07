package com.hms.repository;

import com.hms.entity.Opd;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface OpdRepository extends JpaRepository<Opd, Long> {

	Page<Opd> findByPatient_HospitalId(Long hospitalId, Pageable pageable);

	/**
	 * The only way to load a single OPD.
	 *
	 * <p>Opd has no hospital_id of its own, so tenancy is proven through the owning patient. The
	 * WHERE on p.hospitalId makes the patient join effectively inner: an OPD whose patient cannot
	 * be tenant-verified is never returned. A cross-tenant id therefore yields empty, which
	 * callers surface as 404.
	 *
	 * <p>It also eagerly fetches patient and doctor, so callers can read their fields after the
	 * transaction closes -- with spring.jpa.open-in-view=false a plain findById() leaves them as
	 * lazy proxies that throw once touched, which is why PDF generation needs this shape.
	 *
	 * <p>An unscoped twin of this query used to sit directly above it. Because Opd carries no
	 * hospital_id, the usual fallback of loading by id and comparing entity.getHospitalId()
	 * afterwards is not available here -- there is nothing to compare. The scoped query is the only
	 * safe way to fetch one OPD, so the unscoped one was removed rather than left as something to
	 * reach for by mistake. OpdRepositoryScopingArchTest keeps it gone.
	 */
	@Query("SELECT o FROM Opd o LEFT JOIN FETCH o.patient p LEFT JOIN FETCH o.doctor "
			+ "WHERE o.id = :id AND p.hospitalId = :hospitalId")
	Optional<Opd> findByIdAndHospitalIdWithPatientAndDoctor(@Param("id") Long id,
			@Param("hospitalId") Long hospitalId);

	/** Locks the tenant-scoped OPD before converting it into an IPD admission. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM Opd o LEFT JOIN FETCH o.patient p LEFT JOIN FETCH o.doctor "
			+ "WHERE o.id = :id AND p.hospitalId = :hospitalId")
	Optional<Opd> findByIdAndHospitalIdWithPatientAndDoctorForUpdate(@Param("id") Long id,
			@Param("hospitalId") Long hospitalId);

	@Query(value = "SELECT DISTINCT o FROM Opd o " +
			"INNER JOIN FETCH o.patient p " +
			"LEFT JOIN FETCH o.doctor d " +
			"LEFT JOIN FETCH o.receptionist " +
			"WHERE p.hospitalId = :hospitalId " +
			"AND (:status IS NULL OR o.status = :status) " +
			"AND (:search IS NULL OR LOWER(o.caseId) LIKE LOWER(CONCAT('%',:search,'%')) " +
			"OR LOWER(p.name) LIKE LOWER(CONCAT('%',:search,'%')) " +
			"OR LOWER(d.name) LIKE LOWER(CONCAT('%',:search,'%'))) " +
			"AND (:startDate IS NULL OR o.createdAt >= :startDate) " +
			"AND (:endDate IS NULL OR o.createdAt <= :endDate)",
		countQuery = "SELECT COUNT(DISTINCT o) FROM Opd o " +
			"INNER JOIN o.patient p " +
			"LEFT JOIN o.doctor d " +
			"WHERE p.hospitalId = :hospitalId " +
			"AND (:status IS NULL OR o.status = :status) " +
			"AND (:search IS NULL OR LOWER(o.caseId) LIKE LOWER(CONCAT('%',:search,'%')) " +
			"OR LOWER(p.name) LIKE LOWER(CONCAT('%',:search,'%')) " +
			"OR LOWER(d.name) LIKE LOWER(CONCAT('%',:search,'%'))) " +
			"AND (:startDate IS NULL OR o.createdAt >= :startDate) " +
			"AND (:endDate IS NULL OR o.createdAt <= :endDate)")
	Page<Opd> searchByHospitalAndDateRange(
			@Param("hospitalId") Long hospitalId,
			@Param("search") String search,
			@Param("startDate") java.time.LocalDateTime startDate,
			@Param("endDate") java.time.LocalDateTime endDate,
			@Param("status") com.hms.entity.Opd.Status status,
			Pageable pageable);

	/** Half-open activity interval; preserve the inclusive search API for its other callers. */
	@Query(value = "SELECT o FROM Opd o JOIN FETCH o.patient p LEFT JOIN FETCH o.doctor "
			+ "LEFT JOIN FETCH o.receptionist WHERE p.hospitalId = :hospitalId "
			+ "AND o.createdAt >= :from AND o.createdAt < :toExclusive",
		countQuery = "SELECT COUNT(o) FROM Opd o JOIN o.patient p WHERE p.hospitalId = :hospitalId "
			+ "AND o.createdAt >= :from AND o.createdAt < :toExclusive")
	Page<Opd> findActivityInDateRange(@Param("hospitalId") Long hospitalId,
			@Param("from") java.time.LocalDateTime from,
			@Param("toExclusive") java.time.LocalDateTime toExclusive, Pageable pageable);

	/**
	 * Admissions the doctor recommended that reception has not yet acted on.
	 *
	 * <p>Tenancy is proven through the owning patient, exactly as the single-OPD finder above --
	 * Opd carries no hospital_id, so the join is the only place the tenant can be established.
	 * An OPD already converted to an inpatient stay (IN_IPD) is no longer a pending request.
	 */
	@Query("SELECT COUNT(o) FROM Opd o JOIN o.patient p "
			+ "WHERE p.hospitalId = :hospitalId "
			+ "AND o.ipdAdmitRecommended = TRUE "
			+ "AND o.status <> :excludedStatus")
	long countPendingIpdRequests(@Param("hospitalId") Long hospitalId,
			@Param("excludedStatus") com.hms.entity.Opd.Status excludedStatus);

	/** The paged list behind the count above; same tenant and filter rules. */
	@Query(value = "SELECT o FROM Opd o "
			+ "INNER JOIN FETCH o.patient p "
			+ "LEFT JOIN FETCH o.doctor "
			+ "WHERE p.hospitalId = :hospitalId "
			+ "AND o.ipdAdmitRecommended = TRUE "
			+ "AND o.status <> :excludedStatus",
		countQuery = "SELECT COUNT(o) FROM Opd o JOIN o.patient p "
			+ "WHERE p.hospitalId = :hospitalId "
			+ "AND o.ipdAdmitRecommended = TRUE "
			+ "AND o.status <> :excludedStatus")
	Page<Opd> findPendingIpdRequests(@Param("hospitalId") Long hospitalId,
			@Param("excludedStatus") com.hms.entity.Opd.Status excludedStatus,
			Pageable pageable);

/**
	 * CLIN-P1: every OPD encounter for one patient, tenant-proven through the patient join —
	 * same reasoning as findByIdAndHospitalIdWithPatientAndDoctor above.
	 */
	@Query("SELECT o FROM Opd o WHERE o.patient.id = :patientId AND o.patient.hospitalId = :hospitalId "
			+ "ORDER BY o.createdAt ASC")
	java.util.List<Opd> findByPatientAndHospitalIdOrderByCreatedAtAsc(
			@Param("patientId") Long patientId, @Param("hospitalId") Long hospitalId);

	/**
	 * OPD visits in the window, grouped by visit type. The block's total is the sum of these, so
	 * one statement answers both "how many" and "what kind".
	 *
	 * <p>Tenancy is the patient's. The opd table has no hospital_id at all — an OPD belongs to a
	 * hospital only because its patient does — so every dashboard aggregate joins through
	 * o.patient. An inner join makes that a filter, not a decoration: a visit whose patient is
	 * invisible to this tenant cannot appear in its counts.
	 *
	 * <p>visit_type is nullable, so callers must keep the null group rather than drop it, or the
	 * breakdown stops adding up to the total it sits next to.
	 */
	@Query("SELECT o.visitType, COUNT(o) FROM Opd o JOIN o.patient p "
			+ "WHERE p.hospitalId = :hospitalId "
			+ "AND o.createdAt >= :from AND o.createdAt < :toExclusive "
			+ "GROUP BY o.visitType")
	java.util.List<Object[]> countByVisitTypeInRange(@Param("hospitalId") Long hospitalId,
			@Param("from") java.time.LocalDateTime from,
			@Param("toExclusive") java.time.LocalDateTime toExclusive);

	/**
	 * Daily OPD counts for the trend, aggregated in SQL.
	 *
	 * <p>Native because DATE() is not portable JPQL; MySQL and H2-in-MySQL-mode both provide it.
	 * Returns only the days that actually have visits — the caller zero-fills the rest, so a quiet
	 * Sunday is a zero on the chart rather than a hole in it.
	 */
	@Query(value = "SELECT DATE(o.created_at) AS d, COUNT(*) AS c FROM opd o "
			+ "JOIN patients p ON p.id = o.patient_id "
			+ "WHERE p.hospital_id = :hospitalId "
			+ "AND o.created_at >= :from AND o.created_at < :toExclusive "
			+ "GROUP BY DATE(o.created_at)", nativeQuery = true)
	java.util.List<Object[]> countPerDayInRange(@Param("hospitalId") Long hospitalId,
			@Param("from") java.time.LocalDateTime from,
			@Param("toExclusive") java.time.LocalDateTime toExclusive);

	/**
	 * Busiest specialities, and the one query in this dashboard with a security shape worth
	 * reading twice.
	 *
	 * <p>The doctor is joined for one attribute — the speciality label — and must never influence
	 * which visits are counted. So the tenant predicate stays on the patient, the doctor join is a
	 * LEFT JOIN, and the same-hospital check lives inside the CASE rather than in the WHERE. Put
	 * that check in the WHERE and an OPD attended by another tenant's doctor would silently vanish
	 * from this hospital's totals; put it in the CASE and the visit still counts, it simply counts
	 * as Unassigned. A doctor row from another hospital therefore contributes a bucket name that
	 * came from this tenant's own vocabulary, never that hospital's speciality metadata.
	 *
	 * <p>The unassigned bucket comes back as NULL rather than a bound label: a parameter inside
	 * the CASE renders differently in SELECT and GROUP BY, which strict databases reject. The
	 * caller names it. Unassigned also absorbs a missing doctor and a blank speciality, so the sum
	 * of the buckets always equals the OPD total.
	 *
	 * <p>Ordering is count first and then the bucket itself, because count alone is not a total
	 * order: two specialities tied on the fifth row would swap places between requests and the
	 * chart would reshuffle while nothing changed. The tie-break repeats the CASE rather than
	 * naming the doctor's column, so it orders by the same value the caller sees and cannot order
	 * by a foreign tenant's speciality.
	 */
	@Query("SELECT CASE WHEN d.id IS NOT NULL AND d.hospitalId = p.hospitalId "
			+ "AND d.specialization IS NOT NULL AND TRIM(d.specialization) <> '' "
			+ "THEN d.specialization ELSE NULL END, COUNT(o) "
			+ "FROM Opd o JOIN o.patient p LEFT JOIN o.doctor d "
			+ "WHERE p.hospitalId = :hospitalId "
			+ "AND o.createdAt >= :from AND o.createdAt < :toExclusive "
			+ "GROUP BY CASE WHEN d.id IS NOT NULL AND d.hospitalId = p.hospitalId "
			+ "AND d.specialization IS NOT NULL AND TRIM(d.specialization) <> '' "
			+ "THEN d.specialization ELSE NULL END "
			+ "ORDER BY COUNT(o) DESC, CASE WHEN d.id IS NOT NULL AND d.hospitalId = p.hospitalId "
			+ "AND d.specialization IS NOT NULL AND TRIM(d.specialization) <> '' "
			+ "THEN d.specialization ELSE NULL END ASC")
	java.util.List<Object[]> countBySpecialityInRange(@Param("hospitalId") Long hospitalId,
			@Param("from") java.time.LocalDateTime from,
			@Param("toExclusive") java.time.LocalDateTime toExclusive,
			Pageable pageable);
}

package com.hms.repository;

import com.hms.entity.Hospital;
import com.hms.entity.HospitalType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

@Repository
public interface HospitalRepository extends JpaRepository<Hospital, Long> {

    Optional<Hospital> findByPublicId(String publicId);

    List<Hospital> findAllByOrderByCreatedAtDesc();

    Page<Hospital> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Hospital> findByTypeOrderByCreatedAtDesc(HospitalType type, Pageable pageable);

    long countByIsActive(boolean isActive);

    long countByType(HospitalType type);

    long countByTypeAndIsActive(HospitalType type, boolean isActive);

    List<Hospital> findBySubscriptionStatusIn(List<String> statuses);

    @Query("SELECT DISTINCT h FROM Hospital h JOIN h.modules m WHERE m IN :moduleNames")
    List<Hospital> findByAnyModule(@Param("moduleNames") List<String> moduleNames);
    /**
     * Whether this hospital exists and is currently usable. A missing row and a blocked or expired
     * tenant both answer false, so the authentication filter fails closed on either. Null isActive
     * counts as inactive, matching the login and /auth/me checks.
     */
    @Query("SELECT CASE WHEN COUNT(h) > 0 THEN true ELSE false END FROM Hospital h "
            + "WHERE h.id = :id AND h.isActive = true")
    boolean isActiveTenant(@Param("id") Long id);

    /**
     * A single, durable global row used only to serialize legacy global IPD number allocation.
     * The current IPD number contract is global rather than tenant-local, so locking the caller's
     * hospital would still permit two different hospitals to allocate the same number.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM Hospital h WHERE h.id = (SELECT MIN(h2.id) FROM Hospital h2)")
    Optional<Hospital> findGlobalIpdNumberLock();
}

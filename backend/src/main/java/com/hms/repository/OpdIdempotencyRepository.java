package com.hms.repository;

import com.hms.entity.OpdIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OpdIdempotencyRepository extends JpaRepository<OpdIdempotency, Long> {

    /** Tenant-scoped, matching the unique index this table is built around. */
    Optional<OpdIdempotency> findByHospitalIdAndIdempotencyKey(Long hospitalId, String idempotencyKey);
}

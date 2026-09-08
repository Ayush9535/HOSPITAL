package com.hms.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One claimed OPD registration key.
 *
 * <p>A table of its own rather than a column on {@code opd}, for two reasons.
 *
 * <p>First, tenancy: {@code opd} carries no {@code hospital_id} — its facility is only knowable by
 * joining the owning patient. A global {@code UNIQUE(idempotency_key)} on {@code opd} would let one
 * facility's key collide with another's, so a busy hospital could silently suppress a different
 * hospital's registration. Keeping the claim in its own row restores the tenant column without
 * changing how OPD tenancy works.
 *
 * <p>Second, nullability: a key column on the business entity has to be nullable, because a
 * registration exists whether or not a key was sent. MySQL treats NULLs in a unique index as
 * distinct, so a nullable column silently disables the guarantee for exactly the rows that have
 * none. A row here exists only when a key was supplied, so both columns are NOT NULL and the
 * unique index always means something.
 *
 * <p>{@code opdId} is null only between claiming the key and inserting the OPD. A claim that
 * reaches commit always carries one; a claim still holding null is a request that is either in
 * flight or died before finishing.
 */
@Entity
@Table(name = "opd_idempotency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_opd_idempotency",
                columnNames = {"hospital_id", "idempotency_key"}))
public class OpdIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    /** The registration this key produced. Null only while the request is still running. */
    @Column(name = "opd_id")
    private Long opdId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getHospitalId() { return hospitalId; }
    public void setHospitalId(Long hospitalId) { this.hospitalId = hospitalId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Long getOpdId() { return opdId; }
    public void setOpdId(Long opdId) { this.opdId = opdId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

package com.hms.service.hospital;

import com.hms.entity.OpdIdempotency;
import com.hms.repository.OpdIdempotencyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Each operation on a claim row, in a transaction and a persistence session of its own.
 *
 * <p>Separated from {@link OpdIdempotencyService} because of how Hibernate behaves after a
 * constraint violation. Inserting a duplicate key throws, and the session that threw is finished:
 * reading from it again to find the winner's row raises "don't flush the Session after an
 * exception occurs" rather than returning the row. Under six simultaneous submissions that turned
 * five honest duplicates into HTTP 500s.
 *
 * <p>Splitting the steps across REQUIRES_NEW boundaries means the recovery read runs on a fresh
 * session that never saw the failure. Self-invocation would not have worked — Spring's proxy only
 * applies to calls arriving from outside the bean — which is why this is a separate component
 * rather than more methods on the service.
 */
@Service
public class OpdIdempotencyStore {

    @Autowired
    private OpdIdempotencyRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<OpdIdempotency> find(Long hospitalId, String idempotencyKey) {
        return repository.findByHospitalIdAndIdempotencyKey(hospitalId, idempotencyKey);
    }

    /**
     * Claim the key. Throws when another request already holds it — the unique index on
     * (hospital_id, idempotency_key) is the actual guarantee, not the read above it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long insert(Long hospitalId, String idempotencyKey) {
        OpdIdempotency claim = new OpdIdempotency();
        claim.setHospitalId(hospitalId);
        claim.setIdempotencyKey(idempotencyKey);
        return repository.saveAndFlush(claim).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attachOpd(Long hospitalId, String idempotencyKey, Long opdId) {
        repository.findByHospitalIdAndIdempotencyKey(hospitalId, idempotencyKey).ifPresent(c -> {
            c.setOpdId(opdId);
            repository.save(c);
        });
    }

    /** Drop a claim whose registration failed, so a corrected retry may reuse the key. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteIfUnused(Long hospitalId, String idempotencyKey) {
        repository.findByHospitalIdAndIdempotencyKey(hospitalId, idempotencyKey).ifPresent(c -> {
            if (c.getOpdId() == null) repository.delete(c);
        });
    }
}

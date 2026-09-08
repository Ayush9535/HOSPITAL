package com.hms.service.hospital;

import com.hms.entity.OpdIdempotency;
import com.hms.exception.ConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Claims an OPD registration key, so one logical submission produces one registration.
 *
 * <p>Registering a patient is not repeatable: it inserts the OPD, a queue entry and — under
 * "bill before OPD" — a PAID bill. A double-clicked button or a retried request therefore charged
 * the patient twice and queued them twice, with nothing able to detect it afterwards.
 *
 * <p>Deliberately NOT transactional itself. Every step runs in its own transaction via
 * {@link OpdIdempotencyStore}, so a failed insert cannot poison the session that has to read the
 * winner's row, and the caller's clinical transaction is never entangled with request bookkeeping.
 */
@Service
public class OpdIdempotencyService {

    @Autowired
    private OpdIdempotencyStore store;

    /** What claiming a key told us about this request. */
    public record Claim(boolean isReplay, Long existingOpdId, Long claimId) {}

    /**
     * Take the key for this facility, or report that someone already has it.
     *
     * <p>A replay of a finished request returns the registration it produced. A second request
     * arriving while the first is still running is refused: the honest answer to "am I a
     * duplicate?" is yes, and inventing a second registration to be helpful is the bug itself.
     */
    public Claim claim(Long hospitalId, String idempotencyKey) {
        Optional<OpdIdempotency> existing = store.find(hospitalId, idempotencyKey);
        if (existing.isPresent()) {
            return replayOf(existing.get());
        }
        try {
            return new Claim(false, null, store.insert(hospitalId, idempotencyKey));
        } catch (DataIntegrityViolationException lostTheRace) {
            // Claimed between the read and the insert. The winner's row is authoritative, and it
            // is read on a fresh session — the one that threw cannot be used again.
            return replayOf(store.find(hospitalId, idempotencyKey).orElseThrow(() -> lostTheRace));
        }
    }

    private Claim replayOf(OpdIdempotency claim) {
        if (claim.getOpdId() == null) {
            throw new ConflictException(
                    "This registration is already being processed. Wait for it to finish rather than "
                            + "submitting again.");
        }
        return new Claim(true, claim.getOpdId(), claim.getId());
    }

    public void complete(Long hospitalId, String idempotencyKey, Long opdId) {
        store.attachOpd(hospitalId, idempotencyKey, opdId);
    }

    public void release(Long hospitalId, String idempotencyKey) {
        store.deleteIfUnused(hospitalId, idempotencyKey);
    }
}

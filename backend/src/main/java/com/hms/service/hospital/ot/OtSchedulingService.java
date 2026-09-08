package com.hms.service.hospital.ot;

import com.hms.entity.OtRoom;
import com.hms.entity.Surgery;
import com.hms.repository.OtRoomRepository;
import com.hms.repository.SurgeryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * OtSchedulingService - clash detection for a booking.
 *
 * Two rules, both server-side:
 *  1. A theatre holds one case at a time, and the next case may not start until the room's
 *     turnover has elapsed.
 *  2. A surgeon operates in one theatre at a time. That check is a query over surgeries;
 *     it needs no leave or rostering module, and it prevents the actual harm ("same surgeon,
 *     three rooms, same time") today.
 *
 * Overlap is `start < otherEnd AND end > otherStart`, so cases that merely touch do not
 * clash. Booking runs under a pessimistic lock on the room row, because interval overlap
 * cannot be expressed as a unique index and a read-then-write check races.
 */
@Service
public class OtSchedulingService {

    /** Used when a case carries no estimate; matches the SQL COALESCE in the clash queries. */
    public static final int DEFAULT_DURATION_MINUTES = 60;

    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private OtRoomRepository roomRepository;

    /** Locks the theatre for the rest of the transaction. Call before checking for a clash. */
    public OtRoom lockRoom(Long roomId) {
        return roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Theatre not found"));
    }

    public int durationOf(Surgery surgery) {
        Integer minutes = surgery.getEstimatedDurationMinutes();
        return minutes == null || minutes <= 0 ? DEFAULT_DURATION_MINUTES : minutes;
    }

    /**
     * Rejects a booking that would double-book the theatre or the surgeon.
     * {@code surgery} may already hold the slot (a reschedule), so it excludes itself.
     */
    public void assertSlotIsFree(Long hospitalId, OtRoom room, Surgery surgery, LocalDateTime start) {
        if (start == null) throw new IllegalArgumentException("A date and time are required");

        int duration = durationOf(surgery);
        LocalDateTime end = start.plusMinutes(duration);
        // A case that has not been persisted yet cannot collide with itself.
        Long excludeId = surgery.getId() == null ? -1L : surgery.getId();

        long roomClashes = surgeryRepository.countRoomOverlaps(
                hospitalId, room.getId(), start, end, room.getTurnoverMinutes(), excludeId);
        if (roomClashes > 0) {
            throw new IllegalArgumentException(
                    room.getName() + " is already booked for that time (including " + room.getTurnoverMinutes()
                            + " minutes of turnover)");
        }

        if (surgery.getSurgeonDoctorId() != null) {
            long surgeonClashes = surgeryRepository.countSurgeonOverlaps(
                    hospitalId, surgery.getSurgeonDoctorId(), start, end, excludeId);
            if (surgeonClashes > 0) {
                throw new IllegalArgumentException("That surgeon is already operating at that time");
            }
        }
    }
}

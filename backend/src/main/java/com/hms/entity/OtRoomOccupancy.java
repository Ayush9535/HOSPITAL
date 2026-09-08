package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * OtRoomOccupancy - one span during which a theatre was held by a case.
 *
 * The occupancy timeline that Phase 3's status audit could only estimate. A span opens
 * when a case starts (INCISION-to-close is inside it) and closes when the case completes
 * or is cancelled. Utilisation is the summed span length over available theatre time;
 * turnover is the gap between one span's close and the next span's open in the same room.
 */
@Entity
@Table(name = "ot_room_occupancy", indexes = {
        @Index(name = "idx_occupancy_room", columnList = "ot_room_id,occupied_from"),
        @Index(name = "idx_occupancy_hospital", columnList = "hospital_id,occupied_from")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtRoomOccupancy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "ot_room_id", nullable = false)
    private Long otRoomId;

    @Column(name = "surgery_id", nullable = false)
    private Long surgeryId;

    @Column(name = "occupied_from", nullable = false)
    private LocalDateTime occupiedFrom;

    /** Null while the theatre is still held. */
    @Column(name = "occupied_to")
    private LocalDateTime occupiedTo;
}

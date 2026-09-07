package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * OtRoom - an operation theatre, as a first-class resource.
 *
 * Before this existed an OT was "any ward whose name contains the substring OT",
 * filtered in the browser. "FOOT WARD" qualified, and the server never checked. Capacity
 * was modelled as a single bed, which conflates the room with the thing inside it: a room
 * has a turnover time and can hold back-to-back cases; a bed cannot.
 *
 * Deliberately simple. The occupancy timeline (ot_room_occupancy) is deferred: until it
 * exists, utilisation is derived from surgery_state_transitions, which is accurate and free.
 */
@Entity
@Table(name = "ot_rooms",
        uniqueConstraints = @UniqueConstraint(name = "uk_ot_room_name", columnNames = {"hospital_id", "name"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtRoom {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String OCCUPIED = "OCCUPIED";
    public static final String CLEANING = "CLEANING";
    public static final String MAINTENANCE = "MAINTENANCE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String status = AVAILABLE;

    /** The case currently holding the room, if any. */
    @Column(name = "current_surgery_id")
    private Long currentSurgeryId;

    /** Cleaning and set-up time that must elapse before the next case may start. */
    @Column(name = "turnover_minutes", nullable = false)
    private Integer turnoverMinutes = 15;

    /** Set when a room was migrated from a ward, so the old ward-based rows still resolve. */
    @Column(name = "source_ward_id")
    private Long sourceWardId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) this.publicId = java.util.UUID.randomUUID().toString();
        if (this.status == null) this.status = AVAILABLE;
    }
}

package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** A hospital calendar entry: holiday, event, or notice (Nursing Mgmt Phase G). */
@Entity
@Table(name = "calendar_events")
@Data @NoArgsConstructor @AllArgsConstructor
public class CalendarEvent {
    public static final String HOLIDAY = "HOLIDAY";
    public static final String EVENT = "EVENT";
    public static final String NOTICE = "NOTICE";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(nullable = false, length = 160)
    private String title;
    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;
    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;
    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;
    @Column(length = 500)
    private String description;
    @Column(name = "created_by_user_id")
    private Long createdByUserId;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist public void pre() { if (publicId == null) publicId = java.util.UUID.randomUUID().toString(); }
}

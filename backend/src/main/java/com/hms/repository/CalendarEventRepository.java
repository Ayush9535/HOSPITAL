package com.hms.repository;

import com.hms.entity.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {
    Optional<CalendarEvent> findByPublicId(String publicId);

    // Events overlapping [from,to]: fromDate <= to AND toDate >= from.
    List<CalendarEvent> findByHospitalIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(
            Long hospitalId, LocalDate to, LocalDate from);

    // Active + upcoming events for the management list.
    List<CalendarEvent> findByHospitalIdAndToDateGreaterThanEqualOrderByFromDateAsc(Long hospitalId, LocalDate today);
}

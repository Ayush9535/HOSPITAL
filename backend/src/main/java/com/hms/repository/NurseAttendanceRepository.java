package com.hms.repository;

import com.hms.entity.NurseAttendance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NurseAttendanceRepository extends JpaRepository<NurseAttendance, Long> {
    Optional<NurseAttendance> findByPublicId(String publicId);
    Optional<NurseAttendance> findByNurseProfileIdAndAttendanceDate(Long nurseProfileId, LocalDate date);
    List<NurseAttendance> findByWardIdAndAttendanceDate(Long wardId, LocalDate date);
    List<NurseAttendance> findByNurseProfileIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(Long nurseProfileId, LocalDate from, LocalDate to);
    List<NurseAttendance> findByWardIdInAndAttendanceDateBetween(java.util.Collection<Long> wardIds, LocalDate from, LocalDate to);
}

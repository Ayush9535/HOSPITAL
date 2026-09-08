package com.hms.repository;
import com.hms.entity.NurseShiftSchedule;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate; import java.time.LocalTime; import java.util.List; import java.util.Optional;
public interface NurseShiftScheduleRepository extends JpaRepository<NurseShiftSchedule, Long> {
    Optional<NurseShiftSchedule> findByPublicId(String publicId);
    Optional<NurseShiftSchedule> findByNurseProfileIdAndShiftDate(Long nurseProfileId, LocalDate shiftDate);
    List<NurseShiftSchedule> findByNurseProfileIdAndShiftDateBetweenOrderByShiftDateAsc(Long nurseProfileId, LocalDate from, LocalDate to);
    List<NurseShiftSchedule> findByWardIdAndShiftDateBetweenOrderByShiftDateAsc(Long wardId, LocalDate from, LocalDate to);
    List<NurseShiftSchedule> findByWardIdInAndShiftDateBetween(java.util.Collection<Long> wardIds, LocalDate from, LocalDate to);
    @Modifying(clearAutomatically = true)
    @Query("UPDATE NurseShiftSchedule s SET s.startTime = :start, s.endTime = :end WHERE s.shiftTemplateId = :templateId AND s.shiftDate >= :today")
    int applyTemplateChangeToFuture(@Param("templateId") Long templateId, @Param("start") LocalTime start, @Param("end") LocalTime end, @Param("today") LocalDate today);
}

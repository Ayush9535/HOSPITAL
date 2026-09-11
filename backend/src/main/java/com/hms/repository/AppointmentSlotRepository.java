package com.hms.repository;
import com.hms.entity.AppointmentSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface AppointmentSlotRepository extends JpaRepository<AppointmentSlot, Long> {
    Optional<AppointmentSlot> findByPublicId(String publicId);
    List<AppointmentSlot> findByHospitalIdOrderByStartTimeAsc(Long hospitalId);
    List<AppointmentSlot> findByHospitalIdAndIsActiveTrueOrderByStartTimeAsc(Long hospitalId);
}

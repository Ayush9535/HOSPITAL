package com.hms.repository;
import com.hms.entity.ShiftTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface ShiftTemplateRepository extends JpaRepository<ShiftTemplate, Long> {

    /** Tenant-scoped lookup: a template id read off a schedule row is still resolved in-facility. */
    java.util.Optional<ShiftTemplate> findByIdAndHospitalId(Long id, Long hospitalId);
    Optional<ShiftTemplate> findByPublicId(String publicId);
    List<ShiftTemplate> findByHospitalIdOrderByStartTimeAsc(Long hospitalId);
    List<ShiftTemplate> findByHospitalIdAndIsActiveTrueOrderByStartTimeAsc(Long hospitalId);
}

package com.hms.repository;
import com.hms.entity.BedStatusAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface BedStatusAuditRepository extends JpaRepository<BedStatusAudit, Long> {
    List<BedStatusAudit> findByBedIdOrderByChangedAtDesc(Long bedId);
}

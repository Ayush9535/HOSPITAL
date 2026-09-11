package com.hms.repository;

import com.hms.entity.HospitalServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HospitalServiceItemRepository extends JpaRepository<HospitalServiceItem, Long> {
    List<HospitalServiceItem> findByServiceId(Long serviceId);
    void deleteByServiceId(Long serviceId);
}

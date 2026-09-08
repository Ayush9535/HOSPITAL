package com.hms.repository;

import com.hms.entity.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {
    List<SupportTicket> findByHospitalId(Long hospitalId);

    // Platform admin: Tenant-type isolated tickets
    List<SupportTicket> findByHospitalType(String hospitalType);

    List<SupportTicket> findByHospitalTypeAndStatus(String hospitalType, String status);

    long countByHospitalType(String hospitalType);

    long countByHospitalTypeAndStatus(String hospitalType, String status);
}

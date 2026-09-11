package com.hms.repository;

import com.hms.entity.SurgeryTeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SurgeryTeamMemberRepository extends JpaRepository<SurgeryTeamMember, Long> {
    List<SurgeryTeamMember> findBySurgeryIdOrderByIdAsc(Long surgeryId);
}

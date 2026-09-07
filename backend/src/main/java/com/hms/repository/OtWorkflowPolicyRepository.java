package com.hms.repository;

import com.hms.entity.OtWorkflowPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface OtWorkflowPolicyRepository extends JpaRepository<OtWorkflowPolicy, Long> {

    List<OtWorkflowPolicy> findByHospitalId(Long hospitalId);

    List<OtWorkflowPolicy> findByHospitalIdAndPolicyKey(Long hospitalId, String policyKey);

    @Transactional
    void deleteByHospitalId(Long hospitalId);
}

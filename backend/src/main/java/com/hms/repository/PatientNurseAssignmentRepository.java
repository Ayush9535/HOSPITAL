package com.hms.repository;

import com.hms.entity.PatientNurseAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PatientNurseAssignmentRepository extends JpaRepository<PatientNurseAssignment, Long> {

    Optional<PatientNurseAssignment> findByPublicId(String publicId);

    /** The single active assignment for an admission, if any. */
    Optional<PatientNurseAssignment> findByIpdAdmissionIdAndIsActiveTrue(Long ipdAdmissionId);

    /** CLIN-P1: every assignment (active or historical) across a patient's admissions, for the timeline. */
    List<PatientNurseAssignment> findByIpdAdmissionIdInOrderByAssignedAtAsc(List<Long> ipdAdmissionIds);

    /** All active assignments for a nurse (drives "my patients"). */
    List<PatientNurseAssignment> findByNurseUserIdAndIsActiveTrue(Long nurseUserId);

    /** Active patient load for a nurse (drives least-loaded auto-assignment). */
    long countByNurseUserIdAndIsActiveTrue(Long nurseUserId);

    /** All active assignments for a hospital. */
    List<PatientNurseAssignment> findByHospitalIdAndIsActiveTrue(Long hospitalId);

    /** Full history for an admission, newest first. */
    List<PatientNurseAssignment> findByIpdAdmissionIdOrderByAssignedAtDesc(Long ipdAdmissionId);

    boolean existsByIpdAdmissionIdAndNurseUserIdAndIsActiveTrue(Long ipdAdmissionId, Long nurseUserId);
}

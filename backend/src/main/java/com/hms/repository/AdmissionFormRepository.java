package com.hms.repository;

import com.hms.entity.AdmissionForm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AdmissionFormRepository extends JpaRepository<AdmissionForm, Long> {
    Optional<AdmissionForm> findByIpdAdmissionId(Long ipdAdmissionId);
}

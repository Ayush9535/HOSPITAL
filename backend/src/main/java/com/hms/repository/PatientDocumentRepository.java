package com.hms.repository;

import com.hms.entity.PatientDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Every lookup here is tenant-scoped by signature. A document id on its own is not enough to
 * reach a document, which is the point: the id travels to browsers and back.
 */
@Repository
public interface PatientDocumentRepository extends JpaRepository<PatientDocument, Long> {

    /** A patient's working document list, newest report first. */
    List<PatientDocument> findByHospitalIdAndPatientIdAndIsActiveTrueOrderByReportDateDescIdDesc(
            Long hospitalId, Long patientId);

    /** Every document ever filed for this patient, archived ones included -- the timeline shows
     *  what happened, and something being archived is part of what happened. */
    List<PatientDocument> findByHospitalIdAndPatientIdOrderByIdAsc(Long hospitalId, Long patientId);

    Optional<PatientDocument> findByPublicIdAndHospitalId(String publicId, Long hospitalId);

    Optional<PatientDocument> findByPublicIdAndHospitalIdAndIsActiveTrue(String publicId, Long hospitalId);
}

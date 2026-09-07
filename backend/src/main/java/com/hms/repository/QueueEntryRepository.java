package com.hms.repository;

import com.hms.entity.QueueEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;

public interface QueueEntryRepository extends JpaRepository<QueueEntry, Long> {

        @Query("SELECT DISTINCT q FROM QueueEntry q " +
            "INNER JOIN FETCH q.opd o " +
            "INNER JOIN FETCH o.patient " +
            "LEFT JOIN FETCH o.doctor " +
            "LEFT JOIN FETCH o.receptionist " +
            "INNER JOIN FETCH q.doctor d " +
            "WHERE d.id = :doctorId " +
            "ORDER BY q.createdAt ASC")
        java.util.List<QueueEntry> findQueueForDoctorToday(@Param("doctorId") Long doctorId);

        @Query("SELECT DISTINCT q FROM QueueEntry q " +
            "INNER JOIN FETCH q.opd o " +
            "INNER JOIN FETCH o.patient p " +
            "LEFT JOIN FETCH o.doctor " +
            "LEFT JOIN FETCH o.receptionist " +
            "LEFT JOIN FETCH q.doctor " +
            "WHERE p.hospitalId = :hospitalId " +
            "ORDER BY q.createdAt ASC")
        java.util.List<QueueEntry> findQueueForHospitalToday(@Param("hospitalId") Long hospitalId);

        // Remove queue entries associated with an OPD case
        @Modifying
        @Transactional
        void deleteByOpdId(Long opdId);

        long countByOpdId(Long opdId);
}

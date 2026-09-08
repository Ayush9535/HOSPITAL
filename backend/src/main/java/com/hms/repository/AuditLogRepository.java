package com.hms.repository;

import com.hms.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    // Find logs ordered by timestamp descending (newest first)
    List<AuditLog> findAllByOrderByTimestampDesc();

    // Platform-level actions only: hospitalId is null for Super Admin / platform actions
    // (tenant onboarding, plan changes, password resets), non-null for a tenant's own events.
    List<AuditLog> findByHospitalIdIsNullOrderByTimestampDesc();

    List<AuditLog> findByHospitalIdOrderByTimestampDesc(Long hospitalId);

    List<AuditLog> findByHospitalIdAndActionContainingIgnoreCaseOrderByTimestampDesc(Long hospitalId, String action);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByTimestampDesc(String entityType, String entityId);

    List<AuditLog> findByHospitalIdAndPerformedByRoleOrderByTimestampDesc(Long hospitalId, String performedByRole);

    List<AuditLog> findByHospitalIdAndPerformedByRoleAndActionContainingIgnoreCaseOrderByTimestampDesc(Long hospitalId, String performedByRole, String action);

    List<AuditLog> findByEntityTypeAndEntityIdAndHospitalIdOrderByTimestampDesc(String entityType, String entityId, Long hospitalId);

    List<AuditLog> findByEntityTypeAndHospitalIdAndActionAndEntityIdInOrderByTimestampAsc(String entityType, Long hospitalId, String action, List<String> entityIds);

    // Pharmacy audit logs: only SUPPLIER, MEDICINE_BATCH, PURCHASE_INVOICE, PHARMACY_SALE
    @org.springframework.data.jpa.repository.Query(
        "SELECT a FROM AuditLog a WHERE a.hospitalId = :hid " +
        "AND a.entityType IN ('SUPPLIER', 'MEDICINE_BATCH', 'PURCHASE_INVOICE', 'PHARMACY_SALE') " +
        "AND (:branchId IS NULL OR a.branchId IS NULL OR a.branchId = :branchId) " +
        "AND (:role IS NULL OR a.performedByRole = :role) " +
        "AND (:search IS NULL OR LOWER(a.action) LIKE LOWER(CONCAT('%', :search, '%'))) " +
        "ORDER BY a.timestamp DESC")
    List<AuditLog> findPharmacyLogs(
        @org.springframework.data.repository.query.Param("hid") Long hospitalId,
        @org.springframework.data.repository.query.Param("branchId") Long branchId,
        @org.springframework.data.repository.query.Param("role") String role,
        @org.springframework.data.repository.query.Param("search") String search);
}

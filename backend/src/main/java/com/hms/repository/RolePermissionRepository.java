package com.hms.repository;

import com.hms.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    List<RolePermission> findByHospitalId(Long hospitalId);

    List<RolePermission> findByHospitalIdAndRole(Long hospitalId, String role);

    /** Zero rows for a hospital means "never customised" -- fall back to the defaults. */
    long countByHospitalId(Long hospitalId);

    @Transactional
    void deleteByHospitalId(Long hospitalId);
}

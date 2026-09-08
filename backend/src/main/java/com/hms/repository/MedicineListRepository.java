package com.hms.repository;

import com.hms.entity.MedicineList;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MedicineListRepository extends JpaRepository<MedicineList, Long> {

    List<MedicineList> findByNameContainingIgnoreCase(String query);

    Page<MedicineList> findByNameContainingIgnoreCase(String query, Pageable pageable);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    Optional<MedicineList> findByNameIgnoreCase(String name);

    // Platform admin: Tenant-type isolated medicines
    List<MedicineList> findByHospitalTypeAndNameContainingIgnoreCase(String hospitalType, String query);

    Page<MedicineList> findByHospitalTypeAndNameContainingIgnoreCase(String hospitalType, String query, Pageable pageable);

    boolean existsByHospitalTypeAndNameIgnoreCase(String hospitalType, String name);

    Optional<MedicineList> findByHospitalTypeAndNameIgnoreCase(String hospitalType, String name);
}

package com.hms.repository;

import com.hms.entity.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {
    List<InventoryItem> findByHospitalId(Long hospitalId);

    boolean existsByNameAndHospitalId(String name, Long hospitalId);

    Optional<InventoryItem> findByNameAndHospitalId(String name, Long hospitalId);

    Optional<InventoryItem> findByIdAndHospitalId(Long id, Long hospitalId);

    @Query("SELECT i FROM InventoryItem i WHERE i.hospitalId = :hospitalId AND LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<InventoryItem> searchByName(@Param("query") String query, @Param("hospitalId") Long hospitalId);

    // Platform admin: Tenant-type isolated inventory items
    List<InventoryItem> findByHospitalType(String hospitalType);

    Page<InventoryItem> findByHospitalType(String hospitalType, Pageable pageable);

    boolean existsByHospitalTypeAndName(String hospitalType, String name);

    Optional<InventoryItem> findByHospitalTypeAndName(String hospitalType, String name);

    Optional<InventoryItem> findByHospitalTypeAndId(String hospitalType, Long id);

    @Query("SELECT i FROM InventoryItem i WHERE i.hospitalType = :hospitalType AND LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<InventoryItem> searchByHospitalTypeAndName(@Param("query") String query, @Param("hospitalType") String hospitalType);

    @Query("SELECT i FROM InventoryItem i WHERE i.hospitalType = :hospitalType AND LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<InventoryItem> searchByHospitalTypeAndName(@Param("query") String query, @Param("hospitalType") String hospitalType, Pageable pageable);

    Page<InventoryItem> findByNameContainingIgnoreCase(String name, Pageable pageable);
}

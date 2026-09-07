package com.hms.repository;

import com.hms.entity.InventoryMasterItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryMasterItemRepository extends JpaRepository<InventoryMasterItem, Long> {
    List<InventoryMasterItem> findAllByOrderByNameAsc();
    Page<InventoryMasterItem> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Pageable pageable);
    Optional<InventoryMasterItem> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);
}

package com.hms.repository;

import com.hms.entity.Medicine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MedicineRepository extends JpaRepository<Medicine, Long> {

    // Search for medicines matching the name, specific to this hospital
    @Query("SELECT m FROM Medicine m WHERE lower(m.name) LIKE lower(:query) AND m.hospitalId = :hospitalId AND m.isActive = true")
    List<Medicine> searchByName(String query, Long hospitalId);

    List<Medicine> findByHospitalIdAndIsActiveTrue(Long hospitalId);

    List<Medicine> findByHospitalId(Long hospitalId); // For inventory management (includes inactive)
    
    @Query("SELECT m FROM Medicine m WHERE m.hospitalId = :hospitalId AND m.stockQuantity <= m.minStockLevel AND m.isActive = true")
    List<Medicine> findLowStock(Long hospitalId);

    boolean existsByNameAndHospitalId(String name, Long hospitalId);

    java.util.Optional<Medicine> findByIdAndHospitalId(Long id, Long hospitalId);

    java.util.Optional<Medicine> findByNameIgnoreCaseAndHospitalId(String name, Long hospitalId);

    /**
     * Atomically decrement stock, refusing to go negative.
     *
     * <p>The guard lives in the WHERE clause so the check and the write are a single statement:
     * read-check-then-save loses updates under concurrency (two callers both see the last units,
     * both subtract from the same starting value, and the second write silently overwrites the
     * first). Returns the number of rows updated -- 0 means "not enough stock, nothing changed",
     * which the caller surfaces as a 409 rather than a partial mutation.
     *
     * <p>Also re-asserts hospital_id in the predicate so a mis-scoped caller cannot write across
     * tenants even if an earlier lookup was wrong.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "UPDATE Medicine m SET m.stockQuantity = m.stockQuantity - :qty "
            + "WHERE m.id = :id AND m.hospitalId = :hospitalId AND m.stockQuantity >= :qty")
    int deductStockAtomically(@org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("hospitalId") Long hospitalId,
            @org.springframework.data.repository.query.Param("qty") Integer qty);
}

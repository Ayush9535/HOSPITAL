package com.hms.repository.pharmacy;

import com.hms.entity.pharmacy.PharmacySaleItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * Sale lines, for the one operation that has to change them outside the sale aggregate.
 */
@Repository
public interface PharmacySaleItemRepository extends JpaRepository<PharmacySaleItem, Long> {

    /**
     * Claims part of a sale line for return, atomically.
     *
     * <p>Returns 1 to the caller that fitted inside what was still returnable and 0 to everyone
     * else. The bound lives in the WHERE clause rather than in a preceding read, so two returns
     * submitted together cannot each see the same remaining quantity and both take it — which is
     * exactly how the same sold units could be refunded twice.
     *
     * <p>COALESCE because rows written before this column existed carry NULL, and NULL + 3 is
     * NULL rather than 3.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PharmacySaleItem i "
         + "SET i.returnedQuantity = COALESCE(i.returnedQuantity, 0) + :qty "
         + "WHERE i.id = :id AND COALESCE(i.returnedQuantity, 0) + :qty <= i.quantity")
    int claimReturn(@Param("id") Long id, @Param("qty") BigDecimal qty);
}

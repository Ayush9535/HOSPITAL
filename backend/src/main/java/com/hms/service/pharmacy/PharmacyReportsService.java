package com.hms.service.pharmacy;

import com.hms.security.SecurityContextHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class PharmacyReportsService {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private SecurityContextHelper securityHelper;

    public Map<String, Object> getReportsDashboard() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        Long branchId = securityHelper.getCurrentBranchId();
        Map<String, Object> report = new HashMap<>();

        // 1. Fetch Gross Sales and GST Outputs (Optimized single-pass query)
        String salesQueryStr = "SELECT COALESCE(SUM(s.netAmount), 0), COALESCE(SUM(s.taxAmount), 0) " +
            "FROM PharmacySale s WHERE s.hospitalId = :hospitalId AND s.paymentStatus = 'PAID'";
        if (branchId != null) {
            salesQueryStr += " AND s.branchId = :branchId";
        }
        jakarta.persistence.TypedQuery<Object[]> salesQuery = em.createQuery(salesQueryStr, Object[].class)
            .setParameter("hospitalId", hospitalId);
        if (branchId != null) {
            salesQuery.setParameter("branchId", branchId);
        }
        Object[] salesData = salesQuery.getSingleResult();
        BigDecimal grossSales = (BigDecimal) salesData[0];
        BigDecimal outputGst = (BigDecimal) salesData[1];

        // 2. Fetch Total Patient Reversals / Refunds
        String refundsQueryStr = "SELECT COALESCE(SUM(t.quantity * b.sellingPrice), 0) " +
            "FROM InventoryTransaction t JOIN MedicineBatch b ON t.medicineBatchId = b.id " +
            "WHERE t.hospitalId = :hospitalId AND t.transactionType = 'RETURN' AND t.referenceType = 'PHARMACY_SALE'";
        if (branchId != null) {
            refundsQueryStr += " AND t.branchId = :branchId";
        }
        jakarta.persistence.TypedQuery<BigDecimal> refundsQuery = em.createQuery(refundsQueryStr, BigDecimal.class)
            .setParameter("hospitalId", hospitalId);
        if (branchId != null) {
            refundsQuery.setParameter("branchId", branchId);
        }
        BigDecimal totalRefundAmount = refundsQuery.getSingleResult();

        // 3. Compute Net Revenue
        BigDecimal netRevenue = grossSales.subtract(totalRefundAmount);

        // 4. Calculate COGS (Cost of Goods Sold) using explicit JOINs for maximum performance
        String cogsQueryStr = "SELECT COALESCE(SUM(si.quantity * b.purchaseRate), 0) " +
            "FROM PharmacySaleItem si " +
            "JOIN si.pharmacySale s " +
            "JOIN MedicineBatch b ON si.medicineBatchId = b.id " +
            "WHERE s.hospitalId = :hospitalId AND s.paymentStatus = 'PAID'";
        if (branchId != null) {
            cogsQueryStr += " AND s.branchId = :branchId";
        }
        jakarta.persistence.TypedQuery<BigDecimal> cogsQuery = em.createQuery(cogsQueryStr, BigDecimal.class)
            .setParameter("hospitalId", hospitalId);
        if (branchId != null) {
            cogsQuery.setParameter("branchId", branchId);
        }
        BigDecimal cogs = cogsQuery.getSingleResult();

        // 5. Calculate Gross Profit & Margin Percentage
        BigDecimal grossProfit = netRevenue.subtract(cogs);
        BigDecimal profitMargin = BigDecimal.ZERO;
        if (netRevenue.compareTo(BigDecimal.ZERO) > 0) {
            profitMargin = grossProfit.multiply(BigDecimal.valueOf(100)).divide(netRevenue, 2, RoundingMode.HALF_UP);
        }

        // 6. SINGLE-PASS Batch Aggregate Query (Valuation & Expiry Risks)
        LocalDate today = LocalDate.now();
        LocalDate date30 = today.plusDays(30);
        LocalDate date60 = today.plusDays(60);
        LocalDate date90 = today.plusDays(90);

        String batchQueryStr = "SELECT " +
            "  COALESCE(SUM(CASE WHEN b.expiryDate > :today THEN b.currentQuantity * b.purchaseRate ELSE 0 END), 0), " +
            "  COALESCE(SUM(CASE WHEN b.expiryDate <= :today THEN b.currentQuantity * b.purchaseRate ELSE 0 END), 0), " +
            "  COALESCE(SUM(CASE WHEN b.expiryDate BETWEEN :today AND :date30 THEN b.currentQuantity * b.purchaseRate ELSE 0 END), 0), " +
            "  COALESCE(SUM(CASE WHEN b.expiryDate BETWEEN :date30 AND :date60 THEN b.currentQuantity * b.purchaseRate ELSE 0 END), 0), " +
            "  COALESCE(SUM(CASE WHEN b.expiryDate BETWEEN :date60 AND :date90 THEN b.currentQuantity * b.purchaseRate ELSE 0 END), 0) " +
            "FROM MedicineBatch b WHERE b.hospitalId = :hospitalId AND b.currentQuantity > 0";
        if (branchId != null) {
            batchQueryStr += " AND b.branchId = :branchId";
        }
        jakarta.persistence.TypedQuery<Object[]> batchQuery = em.createQuery(batchQueryStr, Object[].class)
            .setParameter("hospitalId", hospitalId)
            .setParameter("today", today)
            .setParameter("date30", date30)
            .setParameter("date60", date60)
            .setParameter("date90", date90);
        if (branchId != null) {
            batchQuery.setParameter("branchId", branchId);
        }
        Object[] batchSummary = batchQuery.getSingleResult();

        BigDecimal inventoryValuation = (BigDecimal) batchSummary[0];
        BigDecimal expiredLoss = (BigDecimal) batchSummary[1];
        BigDecimal exp30 = (BigDecimal) batchSummary[2];
        BigDecimal exp60 = (BigDecimal) batchSummary[3];
        BigDecimal exp90 = (BigDecimal) batchSummary[4];

        // Compile KPI Map
        Map<String, Object> kpis = new HashMap<>();
        kpis.put("totalSales", grossSales);
        kpis.put("totalRefunds", totalRefundAmount);
        kpis.put("netRevenue", netRevenue);
        kpis.put("inventoryValue", inventoryValuation);
        kpis.put("expiredValue", expiredLoss);
        kpis.put("grossProfit", grossProfit);
        kpis.put("profitMargin", profitMargin);
        report.put("kpis", kpis);

        // 7. Fetch Purchase Input GST
        String purchaseGstQueryStr = "SELECT COALESCE(SUM(p.gstAmount), 0) " +
            "FROM PurchaseInvoice p WHERE p.hospitalId = :hospitalId AND p.postingStatus = 'POSTED'";
        if (branchId != null) {
            purchaseGstQueryStr += " AND p.branchId = :branchId";
        }
        jakarta.persistence.TypedQuery<BigDecimal> purchaseGstQuery = em.createQuery(purchaseGstQueryStr, BigDecimal.class)
            .setParameter("hospitalId", hospitalId);
        if (branchId != null) {
            purchaseGstQuery.setParameter("branchId", branchId);
        }
        BigDecimal inputGst = purchaseGstQuery.getSingleResult();

        Map<String, Object> taxSummary = new HashMap<>();
        taxSummary.put("inputGst", inputGst);
        taxSummary.put("outputGst", outputGst);
        taxSummary.put("netGstPayable", outputGst.subtract(inputGst));
        report.put("taxSummary", taxSummary);

        Map<String, Object> expiryRisk = new HashMap<>();
        expiryRisk.put("next30Days", exp30);
        expiryRisk.put("next60Days", exp60);
        expiryRisk.put("next90Days", exp90);
        report.put("expiryRisk", expiryRisk);

        // 8. Fetch Fast-Moving Items using optimized explicit JOINs
        String fastMovingQueryStr = "SELECT m.medicineName, SUM(si.quantity), SUM(si.totalAmount) " +
            "FROM PharmacySaleItem si " +
            "JOIN si.pharmacySale s " +
            "JOIN MedicineBatch b ON si.medicineBatchId = b.id " +
            "JOIN MedicineMaster m ON b.medicineId = m.id " +
            "WHERE s.hospitalId = :hospitalId AND s.paymentStatus = 'PAID'";
        if (branchId != null) {
            fastMovingQueryStr += " AND s.branchId = :branchId";
        }
        fastMovingQueryStr += " GROUP BY m.medicineName ORDER BY SUM(si.quantity) DESC";
        jakarta.persistence.TypedQuery<Object[]> fastMovingQuery = em.createQuery(fastMovingQueryStr, Object[].class)
            .setParameter("hospitalId", hospitalId);
        if (branchId != null) {
            fastMovingQuery.setParameter("branchId", branchId);
        }
        List<Object[]> fastMovingData = fastMovingQuery.setMaxResults(5).getResultList();

        List<Map<String, Object>> fastMoving = new ArrayList<>();
        for (Object[] row : fastMovingData) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", row[0]);
            item.put("quantity", row[1]);
            item.put("revenue", row[2]);
            fastMoving.add(item);
        }
        report.put("fastMoving", fastMoving);

        // 9. Fetch Category Stock Valuations using optimized explicit JOINs
        String categoryQueryStr = "SELECT COALESCE(c.categoryName, 'Other'), SUM(b.currentQuantity * b.purchaseRate), COUNT(b.id) " +
            "FROM MedicineBatch b " +
            "JOIN MedicineMaster m ON b.medicineId = m.id " +
            "LEFT JOIN MedicineCategory c ON m.categoryId = c.id " +
            "WHERE b.hospitalId = :hospitalId AND b.currentQuantity > 0 AND b.expiryDate > :today";
        if (branchId != null) {
            categoryQueryStr += " AND b.branchId = :branchId";
        }
        categoryQueryStr += " GROUP BY c.categoryName";
        jakarta.persistence.TypedQuery<Object[]> categoryQuery = em.createQuery(categoryQueryStr, Object[].class)
            .setParameter("hospitalId", hospitalId)
            .setParameter("today", today);
        if (branchId != null) {
            categoryQuery.setParameter("branchId", branchId);
        }
        List<Object[]> categoryData = categoryQuery.getResultList();

        List<Map<String, Object>> categoryValuation = new ArrayList<>();
        for (Object[] row : categoryData) {
            Map<String, Object> cat = new HashMap<>();
            cat.put("category", row[0]);
            cat.put("value", row[1]);
            cat.put("count", row[2]);
            categoryValuation.add(cat);
        }
        report.put("categoryValuation", categoryValuation);

        // 10. LIGHTNING-FAST 7-Day Trend: Pull raw datasets in bulk and group in memory
        LocalDateTime startOfWeek = today.minusDays(6).atStartOfDay();
        
        String rawSalesQueryStr = "SELECT s.createdAt, s.netAmount " +
            "FROM PharmacySale s " +
            "WHERE s.hospitalId = :hospitalId AND s.paymentStatus = 'PAID' AND s.createdAt >= :startOfWeek";
        if (branchId != null) {
            rawSalesQueryStr += " AND s.branchId = :branchId";
        }
        jakarta.persistence.TypedQuery<Object[]> rawSalesQuery = em.createQuery(rawSalesQueryStr, Object[].class)
            .setParameter("hospitalId", hospitalId)
            .setParameter("startOfWeek", startOfWeek);
        if (branchId != null) {
            rawSalesQuery.setParameter("branchId", branchId);
        }
        List<Object[]> rawSales = rawSalesQuery.getResultList();

        String rawRefundsQueryStr = "SELECT t.createdAt, t.quantity * b.sellingPrice " +
            "FROM InventoryTransaction t " +
            "JOIN MedicineBatch b ON t.medicineBatchId = b.id " +
            "WHERE t.hospitalId = :hospitalId AND t.transactionType = 'RETURN' AND t.referenceType = 'PHARMACY_SALE' " +
            "AND t.createdAt >= :startOfWeek";
        if (branchId != null) {
            rawRefundsQueryStr += " AND t.branchId = :branchId";
        }
        jakarta.persistence.TypedQuery<Object[]> rawRefundsQuery = em.createQuery(rawRefundsQueryStr, Object[].class)
            .setParameter("hospitalId", hospitalId)
            .setParameter("startOfWeek", startOfWeek);
        if (branchId != null) {
            rawRefundsQuery.setParameter("branchId", branchId);
        }
        List<Object[]> rawRefunds = rawRefundsQuery.getResultList();

        // Map sales to calendar days in memory
        Map<String, BigDecimal> salesByDay = new HashMap<>();
        for (Object[] row : rawSales) {
            LocalDateTime dt = (LocalDateTime) row[0];
            BigDecimal amount = (BigDecimal) row[1];
            if (dt != null && amount != null) {
                String dayKey = dt.toLocalDate().toString();
                salesByDay.put(dayKey, salesByDay.getOrDefault(dayKey, BigDecimal.ZERO).add(amount));
            }
        }

        // Map refunds to calendar days in memory
        Map<String, BigDecimal> refundsByDay = new HashMap<>();
        for (Object[] row : rawRefunds) {
            LocalDateTime dt = (LocalDateTime) row[0];
            BigDecimal amount = (BigDecimal) row[1];
            if (dt != null && amount != null) {
                String dayKey = dt.toLocalDate().toString();
                refundsByDay.put(dayKey, refundsByDay.getOrDefault(dayKey, BigDecimal.ZERO).add(amount));
            }
        }

        // Assemble unified trend report
        List<Map<String, Object>> salesTrend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            String dayKey = date.toString();

            Map<String, Object> dayMap = new HashMap<>();
            dayMap.put("date", dayKey);
            dayMap.put("sales", salesByDay.getOrDefault(dayKey, BigDecimal.ZERO));
            dayMap.put("refunds", refundsByDay.getOrDefault(dayKey, BigDecimal.ZERO));
            salesTrend.add(dayMap);
        }
        report.put("salesTrend", salesTrend);

        return report;
    }
}

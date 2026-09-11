package com.hms.entity.pharmacy;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "medicine_master")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MedicineMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** Multi Pharmacy branch scope; null for non-branch tenants. */
    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "medicine_code", unique = true)
    private String medicineCode;

    @Column(name = "medicine_name", nullable = false)
    private String medicineName;

    @Column(name = "generic_name")
    private String genericName;

    @Column(name = "category_id")
    private Long categoryId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "category_id", insertable = false, updatable = false)
    private MedicineCategory category;

    @Column(name = "manufacturer_id")
    private Long manufacturerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manufacturer_id", insertable = false, updatable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Manufacturer manufacturer;

    @Column(name = "medicine_type")
    private String medicineType; // TABLET, SYRUP, INJECTION

    // Free-text manufacturer captured at purchase time (standalone pharmacy ERP).
    // Separate from manufacturer_id, which links the pharmacy's manufacturer master.
    @Column(name = "manufacturer_name")
    private String manufacturerName;

    @Column(name = "schedule_type")
    private String scheduleType; // H, H1, X, OTC

    @Column(name = "dosage_form")
    private String dosageForm;

    private String strength;

    @Column(name = "unit_of_measure")
    private String unitOfMeasure;

    @Column(name = "reorder_level")
    private Integer reorderLevel = 0;

    @Column(name = "min_stock_level")
    private Integer minStockLevel = 0;

    @Column(name = "gst_percentage")
    private BigDecimal gstPercentage;

    @Column(name = "requires_prescription")
    private Boolean requiresPrescription = true;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

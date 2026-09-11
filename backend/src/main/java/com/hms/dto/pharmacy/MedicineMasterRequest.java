package com.hms.dto.pharmacy;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MedicineMasterRequest {
    @Size(max = 100, message = "Medicine code cannot exceed 100 characters")
    @NoEmoji
    private String medicineCode;

    @NotBlank(message = "Medicine name is required")
    @Size(max = 200, message = "Medicine name cannot exceed 200 characters")
    @NoEmoji
    private String medicineName;

    @Size(max = 200, message = "Generic name cannot exceed 200 characters")
    @NoEmoji
    private String genericName;

    @NotNull(message = "Category ID is required")
    private Long categoryId;

    private Long manufacturerId;

    @Size(max = 100, message = "Medicine type cannot exceed 100 characters")
    @NoEmoji
    private String medicineType;

    @Size(max = 100, message = "Schedule type cannot exceed 100 characters")
    @NoEmoji
    private String scheduleType;

    @Size(max = 100, message = "Dosage form cannot exceed 100 characters")
    @NoEmoji
    private String dosageForm;

    @Size(max = 100, message = "Strength cannot exceed 100 characters")
    @NoEmoji
    private String strength;
    
    @NotBlank(message = "Unit of measure is required")
    @Pattern(regexp = "^(?i)(STRIP|BOTTLE|ML|TABLET|CAPSULE|TUBE|VIAL|BOX|PACK|AMP)$", message = "Invalid unit of measure")
    private String unitOfMeasure;
    
    @Min(value = 0, message = "Reorder level cannot be negative")
    private Integer reorderLevel;

    @Min(value = 0, message = "Minimum stock level cannot be negative")
    private Integer minStockLevel;

    @NotNull(message = "GST percentage is required")
    @DecimalMin(value = "0.0", message = "GST percentage cannot be negative")
    @DecimalMax(value = "100.0", message = "GST percentage cannot exceed 100%")
    private BigDecimal gstPercentage;
    
    private Boolean requiresPrescription;
    
    private Boolean isActive;
}

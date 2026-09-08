package com.hms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrescriptionPresetItemDTO {
    private Long id;
    private String medicineName;
    private String dosage;
    private String frequency;
    private String duration;
    private String instructions;
    private Long medicineId;    // IN_CLINIC: stock link, so applying still deducts stock
    private Integer quantity;   // IN_CLINIC: units to administer
}

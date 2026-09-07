package com.hms.dto;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalServiceDTO {
    private Long id;

    @NotBlank(message = "Service name is required")
    @Size(max = 150, message = "Service name is too long")
    @NoEmoji
    private String name;

    @DecimalMin(value = "0.0", message = "Charge cannot be negative")
    private BigDecimal charge;
    // Relevant items expressed as master item ids (for save) and, on read,
    // enriched names are provided via itemNames.
    private List<Long> masterItemIds;
    private List<String> itemNames;
}

package com.hms.dto;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalFeeDTO {
    private Long id;

    @Size(max = 150, message = "Fee name is too long")
    @NoEmoji
    private String name;

    @DecimalMin(value = "0.0", message = "Fee amount cannot be negative")
    private BigDecimal defaultAmount;
}

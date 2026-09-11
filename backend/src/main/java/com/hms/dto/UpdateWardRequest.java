package com.hms.dto;

import lombok.Data;
import java.math.BigDecimal;
import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Data
public class UpdateWardRequest {
    // fields optional for partial update; when provided, validate basic constraints
    @Size(max = 100)
    @NoEmoji
    private String wardName;

    @PositiveOrZero(message = "bedPrice must be >= 0")
    private BigDecimal bedPrice;

    private Integer floorNumber;

    /**
     * New bed count for the ward. Growing appends fresh available beds; shrinking removes
     * only free beds. Null leaves the bed list untouched (partial update).
     */
    @jakarta.validation.constraints.Min(value = 0, message = "Total beds cannot be negative")
    private Integer totalBeds;

    /**
     * CareUnitRegistry key (ICU Phase 2). Null leaves the classification untouched (partial
     * update). Re-typing a ward that has an occupied bed is rejected — see
     * {@code WardService.updateWard}.
     */
    @Size(max = 20)
    private String unitType;
}

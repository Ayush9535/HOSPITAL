package com.hms.dto;

import lombok.Data;
import java.math.BigDecimal;
import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Data
public class CreateWardRequest {
    @NotBlank(message = "wardName is required")
    @Size(max = 100)
    @NoEmoji
    private String wardName;

    @NotNull(message = "bedPrice is required")
    @PositiveOrZero(message = "bedPrice must be >= 0")
    private BigDecimal bedPrice;

    @NotNull(message = "totalBeds is required")
    @Min(value = 0, message = "totalBeds must be >= 0")
    private Integer totalBeds;

    private Integer floorNumber;

    /**
     * CareUnitRegistry key (ICU Phase 2). Null/blank means GENERAL, so an existing client
     * that does not send it keeps creating general wards exactly as before.
     */
    @Size(max = 20)
    private String unitType;
}

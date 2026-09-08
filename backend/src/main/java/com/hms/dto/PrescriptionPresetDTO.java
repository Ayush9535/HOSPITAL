package com.hms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.hms.validation.NoEmoji;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrescriptionPresetDTO {
    private Long id;

    @NotBlank(message = "Preset name is required")
    @Size(max = 100, message = "Preset name is too long")
    @NoEmoji
    private String name;

    private List<PrescriptionPresetItemDTO> items;
    private Integer displayOrder;
    // doctorId: NULL = shared (all doctors); set = private to that doctor.
    // doctorName is populated on read for the admin view; null when shared.
    private Long doctorId;
    private String doctorName;
    // PRESCRIPTION (default) or IN_CLINIC — two lists sharing one table.
    @Pattern(regexp = "^(PRESCRIPTION|IN_CLINIC)?$", message = "Preset type must be PRESCRIPTION or IN_CLINIC")
    private String presetType;
}

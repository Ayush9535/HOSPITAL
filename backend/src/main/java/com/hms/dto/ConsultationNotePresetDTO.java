package com.hms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.hms.validation.NoEmoji;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationNotePresetDTO {
    private Long id;

    @NotBlank(message = "Field type is required")
    @Size(max = 50, message = "Field type is too long")
    @NoEmoji
    private String fieldType;

    @NotBlank(message = "Text is required")
    @Size(max = 2000, message = "Text is too long")
    @NoEmoji
    private String text;

    private Integer displayOrder;
    // doctorId: NULL = shared (all doctors); set = private to that doctor.
    // doctorName is populated on read for the admin view; null when shared.
    private Long doctorId;
    private String doctorName;
}

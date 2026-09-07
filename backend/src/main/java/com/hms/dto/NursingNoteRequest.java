package com.hms.dto;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Create/update payload for a nursing note.
 */
@Data
public class NursingNoteRequest {
    @Positive
    private Long ipdAdmissionId; // required on create; ignored on update

    @Size(max = 5000)
    @NoEmoji
    private String noteText;

    @Size(max = 5000)
    @NoEmoji
    private String orders;       // optional — drugs / IV fluids printed beside the note

    @Size(max = 60)
    @NoEmoji
    private String category;     // optional (reserved)

    @Positive
    private Long surgeryId;       // optional — links an OT note to its surgery

    @Positive
    private Long performedByNurseId; // optional; required when Separate Nurse Login is OFF
}

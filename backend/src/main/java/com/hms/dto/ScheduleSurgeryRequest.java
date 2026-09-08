package com.hms.dto;

import lombok.Data;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import com.hms.validation.NoEmoji;

/** Schedule payload: assign surgeon + date/time + theatre. */
@Data
public class ScheduleSurgeryRequest {
    /** Revision read by the caller; stale scheduling commands must not overwrite a newer slot. */
    @NotNull(message = "Surgery version is required")
    @PositiveOrZero(message = "Surgery version must not be negative")
    private Long expectedVersion;

    private Long surgeonDoctorId;   // a listed doctor; null when "Other" is chosen

    // Free-text external operator name ("Other"). Not @PersonName: reception legitimately enters
    // degrees with commas ("Dr. Rao, MS"), slashes (the field's own placeholder shows
    // "Dr. anaesthetist / visiting surgeon") or the odd digit — all of which @PersonName rejects.
    @Size(max = 100, message = "Surgeon name is too long")
    @NoEmoji
    private String surgeonName;     // free-text operator name, used when surgeonDoctorId is null ("Other")

    @Size(max = 100, message = "Anaesthetist name is too long")
    @NoEmoji
    private String anaesthetistName; // optional free-text anaesthetist present for the surgery

    @NotNull(message = "Scheduled time is required")
    private LocalDateTime scheduledAt; // required
    /** The theatre. Preferred. */
    private Long otRoomId;
    /** Legacy: a ward whose name contained "OT". Resolved to its room when one was migrated. */
    private Long otWardId;
    /** Drives interval booking; the clash query defaults to 60 minutes when absent. */
    @Positive(message = "Estimated duration must be positive")
    private Integer estimatedDurationMinutes;
}

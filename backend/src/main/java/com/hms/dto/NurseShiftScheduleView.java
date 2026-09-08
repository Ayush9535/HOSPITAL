package com.hms.dto; import com.hms.entity.NurseShiftSchedule; import lombok.Data;
import java.time.LocalDate; import java.time.LocalTime;
@Data public class NurseShiftScheduleView {
    private String publicId; private Long nurseProfileId; private String nurseName; private Long wardId;
    private LocalDate shiftDate; private Long shiftTemplateId; private LocalTime startTime; private LocalTime endTime;
    public static NurseShiftScheduleView of(NurseShiftSchedule s) {
        NurseShiftScheduleView v = new NurseShiftScheduleView();
        v.publicId = s.getPublicId(); v.nurseProfileId = s.getNurseProfileId(); v.wardId = s.getWardId();
        v.shiftDate = s.getShiftDate(); v.shiftTemplateId = s.getShiftTemplateId();
        v.startTime = s.getStartTime(); v.endTime = s.getEndTime(); return v;
    }
}

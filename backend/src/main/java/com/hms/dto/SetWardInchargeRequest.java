package com.hms.dto;

import lombok.Data;

/** Admin: assign (or clear) a ward's Nurse Incharge. */
@Data
public class SetWardInchargeRequest {
    private Long wardId;                  // required
    private Long inchargeNurseProfileId;  // null to clear
}

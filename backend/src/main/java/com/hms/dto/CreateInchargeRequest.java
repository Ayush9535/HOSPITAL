package com.hms.dto;

import lombok.Data;
import java.time.LocalDate;

/** Admin: create a Nurse Incharge (always has a login). */
@Data
public class CreateInchargeRequest {
    private String name;
    private String email;
    private String password;
    private String phone;
    private String gender;
    private String qualification;
    private String registrationNumber;
    private LocalDate joiningDate;
    private Long primaryWardId;
}

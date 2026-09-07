package com.hms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileUpdateRequest {
    private String name;
    private String phone;
    private Integer age;
    private String gender;
    private String specialization; // for Doctor role only
    private String currentPassword;
    private String newPassword;
    private String hospitalName;
    private String hospitalAddress;
    private String hospitalPhone;
    private String logoUrl;
    private String parentOrganization;
}

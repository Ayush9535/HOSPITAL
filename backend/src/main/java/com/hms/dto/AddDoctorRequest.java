package com.hms.dto;

import com.hms.validation.NoEmoji;
import com.hms.validation.PersonName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AddDoctorRequest - DTO for adding a new doctor
 * 
 * This DTO includes doctor information and password for creating a user
 * account.
 * Used by Hospital Admin to add doctors to their hospital.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddDoctorRequest {

    /**
     * Doctor's full name
     */
    @NotBlank(message = "Name is required")
    @PersonName
    private String name;

    /**
     * Doctor's specialization
     */
    @NotBlank(message = "Specialization is required")
    @Size(max = 100, message = "Specialization is too long")
    @NoEmoji
    private String specialization;

    /**
     * Doctor's phone number
     */
    @NotBlank(message = "Phone is required")
    @Pattern(regexp = "^[0-9]{10}$", message = "Phone number must be exactly 10 digits")
    private String phone;

    /**
     * Doctor's email address (used for login)
     */
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 100, message = "Email is too long")
    private String email;

    /**
     * Password for doctor's user account
     */
    @NotBlank(message = "Password is required")
    @Size(max = 200, message = "Password is too long")
    private String password;
}

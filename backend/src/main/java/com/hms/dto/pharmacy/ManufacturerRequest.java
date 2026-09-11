package com.hms.dto.pharmacy;

import com.hms.validation.NoEmoji;
import com.hms.validation.PersonName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ManufacturerRequest {
    @NotBlank(message = "Manufacturer name is required")
    @Size(max = 150, message = "Manufacturer name is too long")
    @NoEmoji
    private String manufacturerName;

    @PersonName(message = "Contact person may only contain letters, spaces and . ' -")
    private String contactPerson;

    @Pattern(regexp = "^(\\d{10})?$", message = "Phone must be exactly 10 digits")
    private String phone;

    @Email(message = "Invalid email format")
    @Size(max = 100, message = "Email is too long")
    private String email;

    @Size(max = 255, message = "Address is too long")
    @NoEmoji
    private String address;

    @Pattern(regexp = "^[A-Za-z0-9/\\-]{0,50}$", message = "License number is invalid")
    private String licenseNumber;

    private Boolean isActive;
}

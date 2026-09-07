package com.hms.dto.pharmacy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import com.hms.validation.NoEmoji;
import com.hms.validation.PersonName;
import lombok.Data;

@Data
public class SupplierRequest {
    @NotBlank(message = "Supplier name is required")
    @Size(max = 150, message = "Supplier name is too long")
    @NoEmoji
    private String supplierName;

    @PersonName(message = "Contact person may only contain letters, spaces and . ' -")
    private String contactPerson;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\d{10}$", message = "Phone must be exactly 10 digits")
    private String phone;

    @Email(message = "Invalid email format")
    @Size(max = 100, message = "Email is too long")
    private String email;

    @Size(max = 255, message = "Address is too long")
    @NoEmoji
    private String address;

    @Pattern(regexp = "^([A-Za-z0-9]{15})?$", message = "GST number must be exactly 15 alphanumeric characters")
    private String gstNumber;

    @Pattern(regexp = "^([A-Za-z0-9]{10})?$", message = "PAN number must be exactly 10 alphanumeric characters")
    private String panNumber;

    @Pattern(regexp = "^[A-Za-z0-9/\\-]{0,50}$", message = "Drug license number is invalid")
    private String drugLicenseNumber;

    @Min(value = 0, message = "Credit days cannot be negative")
    private Integer creditDays;

    private Boolean isActive;
}

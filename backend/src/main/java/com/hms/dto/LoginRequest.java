package com.hms.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 254, message = "Email is too long")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(max = 200, message = "Password is too long")
    private String password;

    /** HOSPITAL | CLINIC | PHARMACY — which portal the user is logging into. Optional, defaults to HOSPITAL. */
    @Pattern(regexp = "^(HOSPITAL|CLINIC|PHARMACY)?$", message = "Invalid portal type")
    private String entityType;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
}

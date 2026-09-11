package com.hms.entity;

import com.hms.validation.NoEmoji;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "medicine_list")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MedicineList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @NotBlank(message = "Medicine name is required")
    @Size(max = 200, message = "Medicine name cannot exceed 200 characters")
    @NoEmoji
    private String name;

    @Column(nullable = false)
    @NotBlank(message = "Type is required")
    @Size(max = 100, message = "Type cannot exceed 100 characters")
    @NoEmoji
    private String type; // e.g., Tablet, Syrup, Injection, Saline, Cream

    @Column(name = "hospital_type")
    private String hospitalType; // HOSPITAL, CLINIC, or PHARMACY - for tenant isolation
}

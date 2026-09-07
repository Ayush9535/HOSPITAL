package com.hms.entity.pharmacy;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "manufacturers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Manufacturer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "manufacturer_name", nullable = false)
    private String manufacturerName;

    @Column(name = "contact_person")
    private String contactPerson;

    private String phone;
    
    private String email;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "license_number")
    private String licenseNumber;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

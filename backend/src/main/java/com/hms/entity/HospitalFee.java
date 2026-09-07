package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/**
 * HospitalFee - Entity representing a custom fee category created by a hospital admin
 * 
 * Each fee has a name and a default amount, and is scoped to a specific hospital.
 * Used for dynamic itemized billing.
 * 
 * @author HMS Team
 * @version Phase-2
 */
@Entity
@Table(name = "hospital_fees")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalFee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "default_amount", precision = 10, scale = 2)
    private BigDecimal defaultAmount;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}

package com.hms.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "hospital_plan_subscriptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalPlanSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false, length = 20)
    private BillingPeriod billingPeriod;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "is_current", nullable = false)
    private Boolean isCurrent = true;
}

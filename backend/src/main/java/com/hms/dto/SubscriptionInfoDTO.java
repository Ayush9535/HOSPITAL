package com.hms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class SubscriptionInfoDTO {
    private String planName;
    private String planType;
    private String billingPeriod;
    private BigDecimal monthlyPrice;
    private BigDecimal yearlyPrice;
    private List<String> features;
    private LocalDateTime assignedAt;
    private LocalDateTime expiresAt;
    private String subscriptionStatus; // ACTIVE | WARNING | EXPIRED
}

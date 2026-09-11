package com.hms.dto;

import lombok.Data;
import java.util.List;

@Data
public class HospitalDetailsDTO {
    private String publicId;
    private String customId;
    private String name;
    private String adminEmail;
    private String adminName;
    private Boolean isActive;
    private List<String> modules;
    private String address;
    private String phone;
    private Boolean isSingleDoctor;
    private String type;
    private String subscriptionStatus;
    private String planName;
    private String billingPeriod;
    private java.time.LocalDateTime assignedAt;
    private java.time.LocalDateTime expiresAt;

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public String getCustomId() {
        return customId;
    }

    public void setCustomId(String customId) {
        this.customId = customId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
    }

    public String getAdminName() {
        return adminName;
    }

    public void setAdminName(String adminName) {
        this.adminName = adminName;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public List<String> getModules() {
        return modules;
    }

    public void setModules(List<String> modules) {
        this.modules = modules;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Boolean getIsSingleDoctor() {
        return isSingleDoctor;
    }

    public void setIsSingleDoctor(Boolean isSingleDoctor) {
        this.isSingleDoctor = isSingleDoctor;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSubscriptionStatus() { return subscriptionStatus; }
    public void setSubscriptionStatus(String subscriptionStatus) { this.subscriptionStatus = subscriptionStatus; }

    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }

    public String getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(String billingPeriod) { this.billingPeriod = billingPeriod; }

    public java.time.LocalDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(java.time.LocalDateTime assignedAt) { this.assignedAt = assignedAt; }

    public java.time.LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(java.time.LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}

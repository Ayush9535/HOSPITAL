# Plan Subscriptions & Entity Types Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Hospital/Clinic/Pharmacy entity types, a dynamic Plan management system, and plan subscriptions with expiry enforcement to the Super Admin platform.

**Architecture:** A new `plans` table stores Super Admin-defined plans (type-scoped, with modules + inClinic flag + pricing). A `hospital_plan_subscriptions` table records each assignment (with expiry). When a plan is assigned or updated, the hospital's `hospital_modules` rows and `HospitalSetting.inClinic` are overwritten automatically. A daily scheduler enforces expiry (WARNING at 7 days, hard-lock at 0 days).

**Tech Stack:** Spring Boot (JPA/Hibernate, `@Scheduled`, Mockito tests), React + Vite, Axios, TanStack Table, Tailwind CSS, MySQL.

---

## File Structure

### Files to CREATE
| File | Purpose |
|---|---|
| `backend/src/main/java/com/hms/entity/HospitalType.java` | Enum: HOSPITAL, CLINIC, PHARMACY |
| `backend/src/main/java/com/hms/entity/BillingPeriod.java` | Enum: MONTHLY, YEARLY |
| `backend/src/main/java/com/hms/entity/Plan.java` | Plan entity mapped to `plans` table |
| `backend/src/main/java/com/hms/entity/HospitalPlanSubscription.java` | Subscription entity mapped to `hospital_plan_subscriptions` |
| `backend/src/main/java/com/hms/repository/PlanRepository.java` | JPA repository for Plan |
| `backend/src/main/java/com/hms/repository/HospitalPlanSubscriptionRepository.java` | JPA repository for subscriptions |
| `backend/src/main/java/com/hms/dto/CreatePlanRequest.java` | DTO for creating/updating a plan |
| `backend/src/main/java/com/hms/dto/AssignPlanRequest.java` | DTO for assigning a plan to an entity |
| `backend/src/main/java/com/hms/dto/SubscriptionInfoDTO.java` | DTO returned to Hospital Admin for their subscription |
| `backend/src/main/java/com/hms/service/platform/PlatformPlanService.java` | Plan CRUD + propagation logic |
| `backend/src/main/java/com/hms/controller/platform/PlatformPlanController.java` | REST endpoints for plan management |
| `backend/src/main/java/com/hms/scheduler/PlanExpiryScheduler.java` | Daily job: WARNING / hard-lock expired subscriptions |
| `backend/src/test/java/com/hms/service/platform/PlatformPlanServiceTest.java` | Mockito unit tests for PlatformPlanService |
| `setup/migrations/V2_plan_subscription_type.sql` | Migration SQL (run once against DB) |
| `frontend/src/components/PlansTab.jsx` | Plans CRUD tab for Super Admin |

### Files to MODIFY
| File | Change |
|---|---|
| `backend/src/main/java/com/hms/entity/Hospital.java` | Add `type` (HospitalType), `subscriptionStatus` (String); remove `plan` String field; update `@PrePersist` for CLN/PHR prefix |
| `backend/src/main/java/com/hms/repository/HospitalRepository.java` | Add `findAllByTypeOrderByCreatedAtDesc`, `countByType`, `findAllBySubscriptionStatusIn` |
| `backend/src/main/java/com/hms/dto/CreateHospitalRequest.java` | Add `type`, `planPublicId`, `billingPeriod` fields |
| `backend/src/main/java/com/hms/dto/HospitalDetailsDTO.java` | Add `type`, `subscriptionStatus`, `planName`, `assignedAt`, `expiresAt`, `billingPeriod` |
| `backend/src/main/java/com/hms/service/platform/PlatformHospitalService.java` | Use `planPublicId` in `createHospital`; remove `updateHospitalPlan` and `updateHospitalModules` methods |
| `backend/src/main/java/com/hms/controller/platform/PlatformHospitalController.java` | Add `type` query param to `GET /platform/hospitals`; remove `/plan` and `/modules` endpoints |
| `frontend/src/services/platformService.js` | Add plan CRUD methods; update `createHospital` to send `type`, `planPublicId`, `billingPeriod` |
| `frontend/src/pages/platform/PlatformDashboard.jsx` | Add Plans tab; entity sub-tabs (Hospital/Clinic/Pharmacy); update create/edit modals |

---

## Task 1: Database Migration SQL

**Files:**
- Create: `setup/migrations/V2_plan_subscription_type.sql`

- [ ] **Step 1: Create the migration file**

```sql
-- V2_plan_subscription_type.sql
-- Run once against the hospital_management database

-- 1. Add type column to hospitals (default HOSPITAL for all existing rows)
ALTER TABLE hospitals
  ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'HOSPITAL',
  ADD COLUMN subscription_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

-- 2. Create plans table
CREATE TABLE plans (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  public_id     VARCHAR(255) NOT NULL,
  name          VARCHAR(100) NOT NULL,
  type          VARCHAR(20)  NOT NULL,
  monthly_price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  yearly_price  DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  in_clinic     BIT(1)       NOT NULL DEFAULT 0,
  is_active     BIT(1)       NOT NULL DEFAULT 1,
  created_at    DATETIME(6)  NOT NULL,
  updated_at    DATETIME(6)  DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plan_public_id (public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. Create plan_modules join table (features enforced at runtime)
CREATE TABLE plan_modules (
  plan_id     BIGINT       NOT NULL,
  module_name VARCHAR(100) NOT NULL,
  FOREIGN KEY (plan_id) REFERENCES plans(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. Create plan_features join table (display labels only, not enforced)
CREATE TABLE plan_features (
  plan_id      BIGINT       NOT NULL,
  feature_name VARCHAR(200) NOT NULL,
  FOREIGN KEY (plan_id) REFERENCES plans(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. Create hospital_plan_subscriptions table
CREATE TABLE hospital_plan_subscriptions (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  hospital_id    BIGINT       NOT NULL,
  plan_id        BIGINT       NOT NULL,
  billing_period VARCHAR(20)  NOT NULL,
  assigned_at    DATETIME(6)  NOT NULL,
  expires_at     DATETIME(6)  NOT NULL,
  assigned_by    BIGINT       DEFAULT NULL,
  is_current     BIT(1)       NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  FOREIGN KEY (hospital_id) REFERENCES hospitals(id),
  FOREIGN KEY (plan_id)     REFERENCES plans(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- NOTE: The existing `plan` VARCHAR(20) column on hospitals is intentionally left in place.
-- It is no longer written by application code; subscription is now via hospital_plan_subscriptions.
```

- [ ] **Step 2: Run against the database**

```bash
mysql -u <user> -p hospital_management < setup/migrations/V2_plan_subscription_type.sql
```

Expected: No errors. Verify:
```sql
SHOW COLUMNS FROM hospitals LIKE 'type';           -- should return 1 row
SHOW TABLES LIKE 'plans';                          -- should return 1 row
SHOW TABLES LIKE 'hospital_plan_subscriptions';    -- should return 1 row
```

- [ ] **Step 3: Commit**

```bash
git add setup/migrations/V2_plan_subscription_type.sql
git commit -m "feat: add V2 migration for entity types, plans, and subscriptions"
```

---

## Task 2: Backend Enums

**Files:**
- Create: `backend/src/main/java/com/hms/entity/HospitalType.java`
- Create: `backend/src/main/java/com/hms/entity/BillingPeriod.java`

- [ ] **Step 1: Create HospitalType enum**

```java
// backend/src/main/java/com/hms/entity/HospitalType.java
package com.hms.entity;

public enum HospitalType {
    HOSPITAL,
    CLINIC,
    PHARMACY
}
```

- [ ] **Step 2: Create BillingPeriod enum**

```java
// backend/src/main/java/com/hms/entity/BillingPeriod.java
package com.hms.entity;

public enum BillingPeriod {
    MONTHLY,
    YEARLY
}
```

- [ ] **Step 3: Compile to verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hms/entity/HospitalType.java \
        backend/src/main/java/com/hms/entity/BillingPeriod.java
git commit -m "feat: add HospitalType and BillingPeriod enums"
```

---

## Task 3: Plan Entity

**Files:**
- Create: `backend/src/main/java/com/hms/entity/Plan.java`

- [ ] **Step 1: Create Plan entity**

```java
// backend/src/main/java/com/hms/entity/Plan.java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "plans")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HospitalType type;

    @Column(name = "monthly_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyPrice = BigDecimal.ZERO;

    @Column(name = "yearly_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal yearlyPrice = BigDecimal.ZERO;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_modules", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "module_name")
    private List<String> modules = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_features", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "feature_name")
    private List<String> features = new ArrayList<>();

    @Column(name = "in_clinic", nullable = false)
    private Boolean inClinic = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void generatePublicId() {
        if (this.publicId == null) {
            this.publicId = java.util.UUID.randomUUID().toString();
        }
    }
}
```

- [ ] **Step 2: Compile to verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/hms/entity/Plan.java
git commit -m "feat: add Plan entity"
```

---

## Task 4: HospitalPlanSubscription Entity

**Files:**
- Create: `backend/src/main/java/com/hms/entity/HospitalPlanSubscription.java`

- [ ] **Step 1: Create HospitalPlanSubscription entity**

```java
// backend/src/main/java/com/hms/entity/HospitalPlanSubscription.java
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
```

- [ ] **Step 2: Compile to verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/hms/entity/HospitalPlanSubscription.java
git commit -m "feat: add HospitalPlanSubscription entity"
```

---

## Task 5: Plan Repository & Subscription Repository

**Files:**
- Create: `backend/src/main/java/com/hms/repository/PlanRepository.java`
- Create: `backend/src/main/java/com/hms/repository/HospitalPlanSubscriptionRepository.java`

- [ ] **Step 1: Create PlanRepository**

```java
// backend/src/main/java/com/hms/repository/PlanRepository.java
package com.hms.repository;

import com.hms.entity.HospitalType;
import com.hms.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanRepository extends JpaRepository<Plan, Long> {

    Optional<Plan> findByPublicId(String publicId);

    List<Plan> findAllByOrderByCreatedAtDesc();

    List<Plan> findByTypeOrderByCreatedAtDesc(HospitalType type);

    long countByIsActiveTrue();
}
```

- [ ] **Step 2: Create HospitalPlanSubscriptionRepository**

```java
// backend/src/main/java/com/hms/repository/HospitalPlanSubscriptionRepository.java
package com.hms.repository;

import com.hms.entity.HospitalPlanSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HospitalPlanSubscriptionRepository extends JpaRepository<HospitalPlanSubscription, Long> {

    Optional<HospitalPlanSubscription> findByHospitalIdAndIsCurrentTrue(Long hospitalId);

    List<HospitalPlanSubscription> findByPlan_IdAndIsCurrentTrue(Long planId);

    long countByPlan_IdAndIsCurrentTrue(Long planId);

    @Modifying
    @Query("UPDATE HospitalPlanSubscription s SET s.isCurrent = false WHERE s.hospitalId = :hospitalId AND s.isCurrent = true")
    void deactivateCurrentSubscription(@Param("hospitalId") Long hospitalId);
}
```

- [ ] **Step 3: Compile to verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hms/repository/PlanRepository.java \
        backend/src/main/java/com/hms/repository/HospitalPlanSubscriptionRepository.java
git commit -m "feat: add PlanRepository and HospitalPlanSubscriptionRepository"
```

---

## Task 6: Update Hospital Entity

**Files:**
- Modify: `backend/src/main/java/com/hms/entity/Hospital.java`

The `plan` String field is removed. Two fields are added: `type` (HospitalType enum) and `subscriptionStatus` (String). The `@PrePersist` method is updated to generate the correct prefix based on type.

- [ ] **Step 1: Replace the Hospital entity**

Open `backend/src/main/java/com/hms/entity/Hospital.java`. Apply these changes:

**a) Add import at the top** (after existing imports):
```java
import com.hms.entity.HospitalType;
```

**b) Remove the `plan` field and its getter/setter entirely** (lines 88-194 region — the field `private String plan = "FREE"` plus `getPlan()` and `setPlan()` methods).

**c) Add two new fields** (after the `isActive` field):
```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HospitalType type = HospitalType.HOSPITAL;

    @Column(name = "subscription_status", nullable = false, length = 20)
    private String subscriptionStatus = "ACTIVE";
```

**d) Replace the entire `@PrePersist` method** with:
```java
    @PrePersist
    public void generateIds() {
        if (this.publicId == null) {
            this.publicId = java.util.UUID.randomUUID().toString();
        }
        if (this.customId == null) {
            String prefix = switch (this.type != null ? this.type : HospitalType.HOSPITAL) {
                case CLINIC   -> "CLN";
                case PHARMACY -> "PHR";
                default       -> "HSP";
            };
            this.customId = prefix + (1000 + new java.util.Random().nextInt(9000));
        }
    }
```

**e) Add getters/setters for new fields** (alongside existing ones):
```java
    public HospitalType getType() { return type; }
    public void setType(HospitalType type) { this.type = type; }

    public String getSubscriptionStatus() { return subscriptionStatus; }
    public void setSubscriptionStatus(String subscriptionStatus) { this.subscriptionStatus = subscriptionStatus; }
```

- [ ] **Step 2: Compile to verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS. If there are compile errors referencing `hospital.getPlan()` or `hospital.setPlan()`, search for those call sites and remove/replace them (they are in `PlatformHospitalService` — addressed in Task 12).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/hms/entity/Hospital.java
git commit -m "feat: add type and subscriptionStatus to Hospital, remove plan string field"
```

---

## Task 7: Update HospitalRepository

**Files:**
- Modify: `backend/src/main/java/com/hms/repository/HospitalRepository.java`

- [ ] **Step 1: Add type-filtered query methods**

Replace the entire file content with:

```java
package com.hms.repository;

import com.hms.entity.Hospital;
import com.hms.entity.HospitalType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HospitalRepository extends JpaRepository<Hospital, Long> {

    Optional<Hospital> findByPublicId(String publicId);

    List<Hospital> findAllByOrderByCreatedAtDesc();

    Page<Hospital> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Hospital> findByTypeOrderByCreatedAtDesc(HospitalType type, Pageable pageable);

    long countByIsActive(boolean isActive);

    long countByType(HospitalType type);

    long countByTypeAndIsActive(HospitalType type, boolean isActive);

    List<Hospital> findBySubscriptionStatusIn(List<String> statuses);
}
```

- [ ] **Step 2: Compile to verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/hms/repository/HospitalRepository.java
git commit -m "feat: add type-filtered query methods to HospitalRepository"
```

---

## Task 8: Create/Update DTOs

**Files:**
- Create: `backend/src/main/java/com/hms/dto/CreatePlanRequest.java`
- Create: `backend/src/main/java/com/hms/dto/AssignPlanRequest.java`
- Create: `backend/src/main/java/com/hms/dto/SubscriptionInfoDTO.java`
- Modify: `backend/src/main/java/com/hms/dto/CreateHospitalRequest.java`
- Modify: `backend/src/main/java/com/hms/dto/HospitalDetailsDTO.java`

- [ ] **Step 1: Create CreatePlanRequest**

```java
// backend/src/main/java/com/hms/dto/CreatePlanRequest.java
package com.hms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreatePlanRequest {

    @NotBlank(message = "Plan name is required")
    private String name;

    @NotNull(message = "Plan type is required")
    private String type; // HOSPITAL | CLINIC | PHARMACY

    @NotNull(message = "Monthly price is required")
    private BigDecimal monthlyPrice;

    @NotNull(message = "Yearly price is required")
    private BigDecimal yearlyPrice;

    private List<String> modules;

    private List<String> features;

    private Boolean inClinic = false;
}
```

- [ ] **Step 2: Create AssignPlanRequest**

```java
// backend/src/main/java/com/hms/dto/AssignPlanRequest.java
package com.hms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssignPlanRequest {

    @NotBlank(message = "Hospital public ID is required")
    private String hospitalPublicId;

    @NotNull(message = "Billing period is required (MONTHLY or YEARLY)")
    private String billingPeriod; // MONTHLY | YEARLY
}
```

- [ ] **Step 3: Create SubscriptionInfoDTO**

```java
// backend/src/main/java/com/hms/dto/SubscriptionInfoDTO.java
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
```

- [ ] **Step 4: Update CreateHospitalRequest** — add `type`, `planPublicId`, `billingPeriod`

Open `backend/src/main/java/com/hms/dto/CreateHospitalRequest.java` and add these three fields (after `isSingleDoctor`):

```java
    @NotBlank(message = "Entity type is required")
    private String type; // HOSPITAL | CLINIC | PHARMACY

    @NotBlank(message = "Plan is required")
    private String planPublicId;

    @NotBlank(message = "Billing period is required")
    private String billingPeriod; // MONTHLY | YEARLY
```

Also add standard getters/setters for those three fields (or rely on Lombok `@Data` — the class already has it, so just adding the fields is enough).

- [ ] **Step 5: Update HospitalDetailsDTO** — add type and subscription fields

Open `backend/src/main/java/com/hms/dto/HospitalDetailsDTO.java`. Add after the `isSingleDoctor` field:

```java
    private String type;
    private String subscriptionStatus;
    private String planName;
    private String billingPeriod;
    private java.time.LocalDateTime assignedAt;
    private java.time.LocalDateTime expiresAt;
```

Also add getters/setters for each:
```java
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
```

Also remove the existing `plan` String field and its getter/setter from this DTO.

- [ ] **Step 6: Compile to verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/hms/dto/CreatePlanRequest.java \
        backend/src/main/java/com/hms/dto/AssignPlanRequest.java \
        backend/src/main/java/com/hms/dto/SubscriptionInfoDTO.java \
        backend/src/main/java/com/hms/dto/CreateHospitalRequest.java \
        backend/src/main/java/com/hms/dto/HospitalDetailsDTO.java
git commit -m "feat: add plan DTOs, update CreateHospitalRequest and HospitalDetailsDTO"
```

---

## Task 9: PlatformPlanService

**Files:**
- Create: `backend/src/main/java/com/hms/service/platform/PlatformPlanService.java`

This service handles:
1. Plan CRUD (create, update, delete with active-subscriber guard)
2. Plan assignment to hospital (sets modules + inClinic + creates subscription row)
3. Plan update propagation (updates all current subscribers' modules + inClinic)

- [ ] **Step 1: Write the failing test first** (see Task 10 — come back here after Task 10 stub)

- [ ] **Step 2: Create PlatformPlanService**

```java
// backend/src/main/java/com/hms/service/platform/PlatformPlanService.java
package com.hms.service.platform;

import com.hms.dto.AssignPlanRequest;
import com.hms.dto.CreatePlanRequest;
import com.hms.dto.SubscriptionInfoDTO;
import com.hms.entity.*;
import com.hms.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class PlatformPlanService {

    @Autowired private PlanRepository planRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private HospitalPlanSubscriptionRepository subscriptionRepository;
    @Autowired private HospitalSettingRepository hospitalSettingRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;

    // ─── Plan CRUD ─────────────────────────────────────────────────────────

    @Transactional
    public Plan createPlan(CreatePlanRequest req) {
        Plan plan = new Plan();
        plan.setName(req.getName());
        plan.setType(HospitalType.valueOf(req.getType()));
        plan.setMonthlyPrice(req.getMonthlyPrice());
        plan.setYearlyPrice(req.getYearlyPrice());
        plan.setModules(req.getModules() != null ? req.getModules() : new ArrayList<>());
        plan.setFeatures(req.getFeatures() != null ? req.getFeatures() : new ArrayList<>());
        plan.setInClinic(Boolean.TRUE.equals(req.getInClinic()));
        plan.setIsActive(true);
        Plan saved = planRepository.save(plan);
        logAction("PLAN_CREATED", "Created plan: " + saved.getName() + " [" + saved.getType() + "]");
        return saved;
    }

    @Transactional
    public Plan updatePlan(String publicId, CreatePlanRequest req) {
        Plan plan = planRepository.findByPublicId(publicId)
                .orElseThrow(() -> new RuntimeException("Plan not found"));

        plan.setName(req.getName());
        plan.setMonthlyPrice(req.getMonthlyPrice());
        plan.setYearlyPrice(req.getYearlyPrice());
        plan.setInClinic(Boolean.TRUE.equals(req.getInClinic()));

        if (req.getModules() != null) {
            plan.getModules().clear();
            plan.getModules().addAll(req.getModules());
        }
        if (req.getFeatures() != null) {
            plan.getFeatures().clear();
            plan.getFeatures().addAll(req.getFeatures());
        }

        Plan saved = planRepository.save(plan);
        propagateModulesToSubscribers(saved);
        logAction("PLAN_UPDATED", "Updated plan: " + saved.getName());
        return saved;
    }

    @Transactional
    public void deletePlan(String publicId) {
        Plan plan = planRepository.findByPublicId(publicId)
                .orElseThrow(() -> new RuntimeException("Plan not found"));

        long activeCount = subscriptionRepository.countByPlan_IdAndIsCurrentTrue(plan.getId());
        if (activeCount > 0) {
            throw new RuntimeException(
                "This plan is assigned to " + activeCount + " active entities. Reassign them before deleting.");
        }

        planRepository.delete(plan);
        logAction("PLAN_DELETED", "Deleted plan: " + plan.getName());
    }

    public List<Plan> getAllPlans() {
        return planRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Plan> getPlansByType(HospitalType type) {
        return planRepository.findByTypeOrderByCreatedAtDesc(type);
    }

    // ─── Plan Assignment ────────────────────────────────────────────────────

    @Transactional
    public HospitalPlanSubscription assignPlan(String planPublicId, AssignPlanRequest req) {
        Plan plan = planRepository.findByPublicId(planPublicId)
                .orElseThrow(() -> new RuntimeException("Plan not found"));

        Hospital hospital = hospitalRepository.findByPublicId(req.getHospitalPublicId())
                .orElseThrow(() -> new RuntimeException("Hospital/Clinic/Pharmacy not found"));

        if (plan.getType() != hospital.getType()) {
            throw new RuntimeException(
                "Plan type '" + plan.getType() + "' does not match entity type '" + hospital.getType() + "'");
        }

        BillingPeriod period = BillingPeriod.valueOf(req.getBillingPeriod());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = period == BillingPeriod.MONTHLY ? now.plusMonths(1) : now.plusYears(1);

        // Deactivate previous subscription
        subscriptionRepository.deactivateCurrentSubscription(hospital.getId());

        // Create new subscription
        HospitalPlanSubscription sub = new HospitalPlanSubscription();
        sub.setHospitalId(hospital.getId());
        sub.setPlan(plan);
        sub.setBillingPeriod(period);
        sub.setAssignedAt(now);
        sub.setExpiresAt(expiresAt);
        sub.setIsCurrent(true);
        sub.setAssignedBy(resolveCurrentUserId());
        HospitalPlanSubscription saved = subscriptionRepository.save(sub);

        // Apply plan modules + inClinic to hospital
        applyPlanToHospital(hospital, plan);

        logAction("PLAN_ASSIGNED",
            "Assigned plan '" + plan.getName() + "' to '" + hospital.getName() + "' [" + period + "]");
        return saved;
    }

    // ─── Subscription Info for Hospital Admin ───────────────────────────────

    public SubscriptionInfoDTO getSubscriptionInfo(Long hospitalId) {
        HospitalPlanSubscription sub = subscriptionRepository
                .findByHospitalIdAndIsCurrentTrue(hospitalId)
                .orElseThrow(() -> new RuntimeException("No active subscription found"));

        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new RuntimeException("Hospital not found"));

        SubscriptionInfoDTO dto = new SubscriptionInfoDTO();
        dto.setPlanName(sub.getPlan().getName());
        dto.setPlanType(sub.getPlan().getType().name());
        dto.setBillingPeriod(sub.getBillingPeriod().name());
        dto.setMonthlyPrice(sub.getPlan().getMonthlyPrice());
        dto.setYearlyPrice(sub.getPlan().getYearlyPrice());
        dto.setFeatures(sub.getPlan().getFeatures());
        dto.setAssignedAt(sub.getAssignedAt());
        dto.setExpiresAt(sub.getExpiresAt());
        dto.setSubscriptionStatus(hospital.getSubscriptionStatus());
        return dto;
    }

    // ─── Internal helpers ───────────────────────────────────────────────────

    private void propagateModulesToSubscribers(Plan plan) {
        List<HospitalPlanSubscription> currentSubs =
                subscriptionRepository.findByPlan_IdAndIsCurrentTrue(plan.getId());
        for (HospitalPlanSubscription sub : currentSubs) {
            hospitalRepository.findById(sub.getHospitalId()).ifPresent(h -> applyPlanToHospital(h, plan));
        }
    }

    private void applyPlanToHospital(Hospital hospital, Plan plan) {
        hospital.setModules(new ArrayList<>(plan.getModules()));
        hospital.setSubscriptionStatus("ACTIVE");
        hospitalRepository.save(hospital);

        hospitalSettingRepository.findByHospital(hospital).ifPresent(setting -> {
            setting.setInClinic(plan.getInClinic());
            hospitalSettingRepository.save(setting);
        });
    }

    private Long resolveCurrentUserId() {
        try {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            return userRepository.findByEmail(email).map(u -> u.getId()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void logAction(String action, String details) {
        try {
            String performedBy = SecurityContextHolder.getContext().getAuthentication().getName();
            AuditLog log = new AuditLog();
            log.setAction(action);
            log.setDetails(details);
            log.setPerformedBy(performedBy);
            auditLogRepository.save(log);
        } catch (Exception e) {
            System.err.println("Audit log failed: " + e.getMessage());
        }
    }
}
```

**Note:** `hospitalSettingRepository.findByHospital(hospital)` requires this method to exist. Check `HospitalSettingRepository` — if it only has `save`, add:
```java
Optional<HospitalSetting> findByHospital(Hospital hospital);
```

Also check `userRepository.findByEmail(email)` — verify this method exists in `UserRepository`. If not, add it:
```java
Optional<User> findByEmail(String email);
```

- [ ] **Step 3: Compile to verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS. Fix any missing repository methods before proceeding.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hms/service/platform/PlatformPlanService.java
git commit -m "feat: add PlatformPlanService with plan CRUD, assignment, and propagation"
```

---

## Task 10: PlatformPlanService Tests

**Files:**
- Create: `backend/src/test/java/com/hms/service/platform/PlatformPlanServiceTest.java`

- [ ] **Step 1: Write the tests**

```java
// backend/src/test/java/com/hms/service/platform/PlatformPlanServiceTest.java
package com.hms.service.platform;

import com.hms.dto.AssignPlanRequest;
import com.hms.dto.CreatePlanRequest;
import com.hms.entity.*;
import com.hms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatformPlanServiceTest {

    @Mock PlanRepository planRepository;
    @Mock HospitalRepository hospitalRepository;
    @Mock HospitalPlanSubscriptionRepository subscriptionRepository;
    @Mock HospitalSettingRepository hospitalSettingRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock UserRepository userRepository;

    @InjectMocks PlatformPlanService service;

    @BeforeEach
    void mockSecurity() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("superadmin@hms.com");
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    @Test
    void createPlan_savesAndReturnsPlan() {
        CreatePlanRequest req = new CreatePlanRequest();
        req.setName("Clinic Essential");
        req.setType("CLINIC");
        req.setMonthlyPrice(new BigDecimal("999.00"));
        req.setYearlyPrice(new BigDecimal("9999.00"));
        req.setModules(List.of("OPD", "BILLING"));
        req.setFeatures(List.of("OPD Management", "GST Billing"));
        req.setInClinic(false);

        Plan savedPlan = new Plan();
        savedPlan.setId(1L);
        savedPlan.setName("Clinic Essential");
        savedPlan.setType(HospitalType.CLINIC);
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);

        Plan result = service.createPlan(req);

        assertThat(result.getName()).isEqualTo("Clinic Essential");
        assertThat(result.getType()).isEqualTo(HospitalType.CLINIC);
        verify(planRepository).save(any(Plan.class));
    }

    @Test
    void deletePlan_throwsWhenActiveSubscribers() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setName("Clinic Essential");
        when(planRepository.findByPublicId("pub-123")).thenReturn(Optional.of(plan));
        when(subscriptionRepository.countByPlan_IdAndIsCurrentTrue(1L)).thenReturn(3L);

        assertThatThrownBy(() -> service.deletePlan("pub-123"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("3 active entities");
    }

    @Test
    void deletePlan_succeedsWhenNoSubscribers() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setName("Old Plan");
        when(planRepository.findByPublicId("pub-456")).thenReturn(Optional.of(plan));
        when(subscriptionRepository.countByPlan_IdAndIsCurrentTrue(1L)).thenReturn(0L);

        service.deletePlan("pub-456");

        verify(planRepository).delete(plan);
    }

    @Test
    void assignPlan_throwsOnTypeMismatch() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setType(HospitalType.CLINIC);
        when(planRepository.findByPublicId("plan-pub")).thenReturn(Optional.of(plan));

        Hospital hospital = new Hospital();
        hospital.setId(10L);
        hospital.setType(HospitalType.HOSPITAL);
        when(hospitalRepository.findByPublicId("hosp-pub")).thenReturn(Optional.of(hospital));

        AssignPlanRequest req = new AssignPlanRequest();
        req.setHospitalPublicId("hosp-pub");
        req.setBillingPeriod("MONTHLY");

        assertThatThrownBy(() -> service.assignPlan("plan-pub", req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("does not match entity type");
    }

    @Test
    void assignPlan_setsModulesAndCreatesSubscription() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setType(HospitalType.HOSPITAL);
        plan.setModules(List.of("OPD", "IPD", "BILLING"));
        plan.setInClinic(false);
        when(planRepository.findByPublicId("plan-pub")).thenReturn(Optional.of(plan));

        Hospital hospital = new Hospital();
        hospital.setId(10L);
        hospital.setType(HospitalType.HOSPITAL);
        hospital.setName("Test Hospital");
        when(hospitalRepository.findByPublicId("hosp-pub")).thenReturn(Optional.of(hospital));
        when(hospitalRepository.save(any())).thenReturn(hospital);
        when(hospitalSettingRepository.findByHospital(any())).thenReturn(Optional.empty());

        HospitalPlanSubscription savedSub = new HospitalPlanSubscription();
        savedSub.setId(1L);
        when(subscriptionRepository.save(any())).thenReturn(savedSub);

        AssignPlanRequest req = new AssignPlanRequest();
        req.setHospitalPublicId("hosp-pub");
        req.setBillingPeriod("MONTHLY");

        HospitalPlanSubscription result = service.assignPlan("plan-pub", req);

        assertThat(result.getId()).isEqualTo(1L);
        verify(subscriptionRepository).deactivateCurrentSubscription(10L);
        verify(hospitalRepository).save(argThat(h -> h.getModules().containsAll(List.of("OPD", "IPD", "BILLING"))));
    }
}
```

- [ ] **Step 2: Run the tests (they should pass since service is already written)**

```bash
cd backend && mvn test -Dtest=PlatformPlanServiceTest -q
```

Expected: All 5 tests PASS

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/hms/service/platform/PlatformPlanServiceTest.java
git commit -m "test: add PlatformPlanService unit tests"
```

---

## Task 11: PlatformPlanController

**Files:**
- Create: `backend/src/main/java/com/hms/controller/platform/PlatformPlanController.java`

- [ ] **Step 1: Create the controller**

```java
// backend/src/main/java/com/hms/controller/platform/PlatformPlanController.java
package com.hms.controller.platform;

import com.hms.dto.AssignPlanRequest;
import com.hms.dto.CreatePlanRequest;
import com.hms.entity.HospitalType;
import com.hms.entity.Plan;
import com.hms.service.platform.PlatformPlanService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/platform/plans")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformPlanController {

    @Autowired
    private PlatformPlanService planService;

    @GetMapping
    public ResponseEntity<List<Plan>> getAllPlans(
            @RequestParam(required = false) String type) {
        if (type != null && !type.isBlank()) {
            return ResponseEntity.ok(planService.getPlansByType(HospitalType.valueOf(type)));
        }
        return ResponseEntity.ok(planService.getAllPlans());
    }

    @PostMapping
    public ResponseEntity<?> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        try {
            Plan plan = planService.createPlan(request);
            return ResponseEntity.ok(plan);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{publicId}")
    public ResponseEntity<?> updatePlan(
            @PathVariable String publicId,
            @Valid @RequestBody CreatePlanRequest request) {
        try {
            Plan plan = planService.updatePlan(publicId, request);
            return ResponseEntity.ok(plan);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<?> deletePlan(@PathVariable String publicId) {
        try {
            planService.deletePlan(publicId);
            return ResponseEntity.ok("Plan deleted successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/{publicId}/assign")
    public ResponseEntity<?> assignPlan(
            @PathVariable String publicId,
            @Valid @RequestBody AssignPlanRequest request) {
        try {
            return ResponseEntity.ok(planService.assignPlan(publicId, request));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Compile to verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/hms/controller/platform/PlatformPlanController.java
git commit -m "feat: add PlatformPlanController with CRUD and assign endpoints"
```

---

## Task 12: Update PlatformHospitalService

**Files:**
- Modify: `backend/src/main/java/com/hms/service/platform/PlatformHospitalService.java`

Changes:
1. `createHospital` uses `planPublicId` + `billingPeriod` from request; delegates to `PlatformPlanService.assignPlan`
2. Remove `updateHospitalPlan` method (plan changes go via `/platform/plans/{id}/assign`)
3. Remove `updateHospitalModules` method (modules are auto-managed by plan)
4. `getHospitalDetails` fills the new DTO fields (type, subscriptionStatus, planName, etc.)
5. `getAllHospitals` accepts optional `type` filter

- [ ] **Step 1: Add PlatformPlanService as a dependency**

In `PlatformHospitalService.java`, add:
```java
@Autowired
private PlatformPlanService planService;
```

- [ ] **Step 2: Update `createHospital` method**

Replace the existing `createHospital` method body with:

```java
@Transactional
public Hospital createHospital(CreateHospitalRequest request) {
    if (userRepository.existsByEmail(request.getAdminEmail())) {
        throw new RuntimeException("Email already exists");
    }

    HospitalType type = HospitalType.valueOf(request.getType());

    Hospital hospital = new Hospital();
    hospital.setName(request.getHospitalName());
    hospital.setIsActive(true);
    hospital.setType(type);
    if (type != HospitalType.PHARMACY && request.getIsSingleDoctor() != null) {
        hospital.setIsSingleDoctor(request.getIsSingleDoctor());
    } else {
        hospital.setIsSingleDoctor(false);
    }
    hospital = hospitalRepository.save(hospital);

    HospitalSetting settings = new HospitalSetting();
    settings.setHospital(hospital);
    settings.setReceptionMode("HAS_RECEPTIONIST");
    settings.setBillingHandler("RECEPTIONIST");
    settings.setInClinic(false);
    hospitalSettingRepository.save(settings);

    User admin = new User();
    admin.setEmail(request.getAdminEmail());
    admin.setPassword(passwordEncoder.encode(request.getAdminPassword()));
    admin.setName(request.getAdminName());
    admin.setRole("HOSPITAL_ADMIN");
    admin.setHospitalId(hospital.getId());
    userRepository.save(admin);

    HospitalAdmin hospitalAdmin = new HospitalAdmin();
    hospitalAdmin.setHospitalId(hospital.getId());
    hospitalAdmin.setName(request.getAdminName());
    hospitalAdmin.setEmail(request.getAdminEmail());
    hospitalAdmin.setPhone("");
    hospitalAdmin.setIsActive(true);
    hospitalAdminRepository.save(hospitalAdmin);

    // For Hospital/Clinic only: single doctor mode creates a doctor profile
    if (type != HospitalType.PHARMACY
            && Boolean.TRUE.equals(hospital.getIsSingleDoctor())) {
        Doctor doctor = new Doctor();
        doctor.setHospitalId(hospital.getId());
        doctor.setEmail(admin.getEmail());
        doctor.setName(admin.getName());
        doctor.setSpecialization("General Physician");
        doctor.setPhone("0000000055");
        doctor.setIsActive(true);
        doctorRepository.save(doctor);
    }

    // Assign plan (also applies modules + inClinic to hospital)
    com.hms.dto.AssignPlanRequest assignReq = new com.hms.dto.AssignPlanRequest();
    assignReq.setHospitalPublicId(hospital.getPublicId());
    assignReq.setBillingPeriod(request.getBillingPeriod());
    planService.assignPlan(request.getPlanPublicId(), assignReq);

    logAction("HOSPITAL_CREATED",
        "Created " + type + ": " + hospital.getName() + " with admin: " + admin.getEmail());

    return hospitalRepository.findById(hospital.getId()).orElse(hospital);
}
```

- [ ] **Step 3: Remove `updateHospitalPlan` and `updateHospitalModules` methods**

Delete the entire `updateHospitalPlan(...)` method (lines ~250-264) and `updateHospitalModules(...)` method (lines ~273-288) from the file.

- [ ] **Step 4: Update `getHospitalDetails` to fill new DTO fields**

In the `getHospitalDetails` method, after building the DTO, add:

```java
dto.setType(hospital.getType() != null ? hospital.getType().name() : "HOSPITAL");
dto.setSubscriptionStatus(hospital.getSubscriptionStatus());

// Fetch current subscription info
subscriptionRepository.findByHospitalIdAndIsCurrentTrue(hospital.getId()).ifPresent(sub -> {
    dto.setPlanName(sub.getPlan().getName());
    dto.setBillingPeriod(sub.getBillingPeriod().name());
    dto.setAssignedAt(sub.getAssignedAt());
    dto.setExpiresAt(sub.getExpiresAt());
});
```

Also add `@Autowired` for `HospitalPlanSubscriptionRepository`:
```java
@Autowired
private HospitalPlanSubscriptionRepository subscriptionRepository;
```

- [ ] **Step 5: Update `getAllHospitals` to support optional type filter**

Replace the existing method with:
```java
public org.springframework.data.domain.Page<Hospital> getAllHospitals(
        org.springframework.data.domain.Pageable pageable,
        String type) {
    if (type != null && !type.isBlank()) {
        return hospitalRepository.findByTypeOrderByCreatedAtDesc(HospitalType.valueOf(type), pageable);
    }
    return hospitalRepository.findAllByOrderByCreatedAtDesc(pageable);
}
```

- [ ] **Step 6: Compile to verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/hms/service/platform/PlatformHospitalService.java
git commit -m "feat: update PlatformHospitalService to use plan assignment and entity types"
```

---

## Task 13: Update PlatformHospitalController

**Files:**
- Modify: `backend/src/main/java/com/hms/controller/platform/PlatformHospitalController.java`

- [ ] **Step 1: Add `type` query param to `GET /platform/hospitals`**

Replace the `getAllHospitals` method:
```java
@GetMapping
public ResponseEntity<Page<Hospital>> getAllHospitals(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(required = false) String type) {
    Pageable pageable = PageRequest.of(page, size);
    Page<Hospital> hospitals = hospitalService.getAllHospitals(pageable, type);
    return ResponseEntity.ok(hospitals);
}
```

- [ ] **Step 2: Remove `/plan` and `/modules` endpoints**

Delete the entire `updateHospitalPlan` method (the `@PutMapping("/{id}/plan")` block) and the `updateHospitalModules` method (the `@PutMapping("/{id}/modules")` block).

- [ ] **Step 3: Compile to verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Run all backend tests**

```bash
cd backend && mvn test -q
```

Expected: All tests PASS (or known pre-existing failures only)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/controller/platform/PlatformHospitalController.java
git commit -m "feat: update PlatformHospitalController — add type filter, remove plan/modules endpoints"
```

---

## Task 14: PlanExpiryScheduler

**Files:**
- Create: `backend/src/main/java/com/hms/scheduler/PlanExpiryScheduler.java`

`@EnableScheduling` is already present on the main application class — no changes needed there.

- [ ] **Step 1: Create the scheduler**

```java
// backend/src/main/java/com/hms/scheduler/PlanExpiryScheduler.java
package com.hms.scheduler;

import com.hms.entity.Hospital;
import com.hms.entity.HospitalPlanSubscription;
import com.hms.repository.HospitalPlanSubscriptionRepository;
import com.hms.repository.HospitalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class PlanExpiryScheduler {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private HospitalPlanSubscriptionRepository subscriptionRepository;

    // Runs every day at 00:05 server time
    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void checkPlanExpiry() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime warningThreshold = now.plusDays(7);

        List<Hospital> activeHospitals = hospitalRepository.findBySubscriptionStatusIn(
                List.of("ACTIVE", "WARNING"));

        for (Hospital hospital : activeHospitals) {
            subscriptionRepository.findByHospitalIdAndIsCurrentTrue(hospital.getId())
                    .ifPresent(sub -> applyExpiryLogic(hospital, sub, now, warningThreshold));
        }
    }

    private void applyExpiryLogic(Hospital hospital, HospitalPlanSubscription sub,
                                   LocalDateTime now, LocalDateTime warningThreshold) {
        if (sub.getExpiresAt().isBefore(now)) {
            // Hard lock
            hospital.setIsActive(false);
            hospital.setSubscriptionStatus("EXPIRED");
            hospitalRepository.save(hospital);
        } else if (sub.getExpiresAt().isBefore(warningThreshold)) {
            // Warning — do not lock, just flag
            hospital.setSubscriptionStatus("WARNING");
            hospitalRepository.save(hospital);
        } else if ("WARNING".equals(hospital.getSubscriptionStatus())) {
            // Was in warning but expiry was extended — reset to ACTIVE
            hospital.setSubscriptionStatus("ACTIVE");
            hospitalRepository.save(hospital);
        }
    }
}
```

- [ ] **Step 2: Compile to verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/hms/scheduler/PlanExpiryScheduler.java
git commit -m "feat: add PlanExpiryScheduler for daily WARNING/EXPIRED enforcement"
```

---

## Task 15: Hospital Admin Subscription Endpoint

The Hospital Admin needs to see their current plan info (name, expiry, status) in their dashboard. Add one endpoint to a hospital-level controller.

**Files:**
- Find the most appropriate hospital-level controller (check `backend/src/main/java/com/hms/controller/hospital/`) — look for `HospitalAdminController.java` or `HospitalSettingController.java`. If neither exists, add the endpoint to whichever controller handles hospital settings or profile.

- [ ] **Step 1: Find the right controller**

```bash
ls backend/src/main/java/com/hms/controller/hospital/
```

- [ ] **Step 2: Add the endpoint**

In the chosen controller (e.g., `HospitalSettingController.java` or `HospitalProfileController.java`), add:

```java
@Autowired
private PlatformPlanService planService;

@Autowired
private com.hms.security.SecurityHelper securityHelper;  // or SecurityContextHelper

@GetMapping("/subscription")
@PreAuthorize("hasRole('HOSPITAL_ADMIN')")
public ResponseEntity<?> getSubscriptionInfo() {
    try {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        com.hms.dto.SubscriptionInfoDTO dto = planService.getSubscriptionInfo(hospitalId);
        return ResponseEntity.ok(dto);
    } catch (Exception e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
```

Map this under `/hospital/subscription`. Verify the base `@RequestMapping` of the chosen controller to ensure the final URL is `GET /hospital/subscription`.

- [ ] **Step 3: Compile and run tests**

```bash
cd backend && mvn test -q
```

Expected: All tests PASS

- [ ] **Step 4: Start the backend and smoke-test the new endpoints**

```bash
cd backend && mvn spring-boot:run
```

Test with a REST client (e.g., Postman or curl):
```bash
# Login as super admin first to get token, then:
curl -H "Authorization: Bearer <token>" http://localhost:8080/platform/plans
# Expected: [] (empty list initially)

curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"Test Plan","type":"HOSPITAL","monthlyPrice":999,"yearlyPrice":9999,"modules":["OPD","BILLING"],"features":["OPD Management"],"inClinic":false}' \
  http://localhost:8080/platform/plans
# Expected: plan object with publicId

curl -H "Authorization: Bearer <token>" http://localhost:8080/platform/plans
# Expected: list with the created plan
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/controller/hospital/
git commit -m "feat: add GET /hospital/subscription endpoint for plan info"
```

---

## Task 16: Frontend — Update platformService.js

**Files:**
- Modify: `frontend/src/services/platformService.js`

- [ ] **Step 1: Add plan CRUD + assign methods; update hospital methods**

Open `frontend/src/services/platformService.js`. Make these changes:

**a) Update `getHospitals` to accept optional `type` param:**
```js
getHospitals: async (page = 0, size = 10, type = '') => {
    const params = { page, size };
    if (type) params.type = type;
    const response = await apiClient.get('/platform/hospitals', { params });
    return response.data;
},
```

**b) Remove `updateHospitalPlan` and `updateHospitalModules` functions entirely.**

**c) Add plan CRUD methods** (after `deleteFaq`):
```js
// ─── Plan Management ─────────────────────────────────────────────────────

getPlans: async (type = '') => {
    const params = type ? { type } : {};
    const response = await apiClient.get('/platform/plans', { params });
    return response.data;
},

createPlan: async (planData) => {
    const response = await apiClient.post('/platform/plans', planData);
    return response.data;
},

updatePlan: async (publicId, planData) => {
    const response = await apiClient.put(`/platform/plans/${publicId}`, planData);
    return response.data;
},

deletePlan: async (publicId) => {
    const response = await apiClient.delete(`/platform/plans/${publicId}`);
    return response.data;
},

assignPlan: async (planPublicId, hospitalPublicId, billingPeriod) => {
    const response = await apiClient.post(`/platform/plans/${planPublicId}/assign`, {
        hospitalPublicId,
        billingPeriod,
    });
    return response.data;
},
```

- [ ] **Step 2: Verify no broken references**

```bash
cd frontend && grep -r "updateHospitalPlan\|updateHospitalModules" src/
```

Expected: No results (if any found, remove those call sites in the dashboard — addressed in Task 18).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/services/platformService.js
git commit -m "feat: update platformService with plan CRUD, assign, and type-filtered hospitals"
```

---

## Task 17: Frontend — PlansTab Component

**Files:**
- Create: `frontend/src/components/PlansTab.jsx`

This component is the full Plans management UI for Super Admin. It shows all plans in a table with type-filter, and provides Create/Edit/Delete actions.

- [ ] **Step 1: Create PlansTab.jsx**

```jsx
// frontend/src/components/PlansTab.jsx
import React, { useState, useEffect } from 'react';
import platformService from '../services/platformService';
import { useToast } from '../context/ToastContext';

const ENTITY_TYPES = ['HOSPITAL', 'CLINIC', 'PHARMACY'];
const AVAILABLE_MODULES = ['OPD', 'IPD', 'PHARMACY', 'BILLING', 'OT', 'PATHOLOGY'];

const emptyForm = {
    name: '',
    type: 'HOSPITAL',
    monthlyPrice: '',
    yearlyPrice: '',
    modules: [],
    features: '',   // newline-separated string → split on save
    inClinic: false,
};

export default function PlansTab() {
    const { success } = useToast();
    const [plans, setPlans] = useState([]);
    const [loading, setLoading] = useState(true);
    const [typeFilter, setTypeFilter] = useState('');
    const [showModal, setShowModal] = useState(false);
    const [editingPlan, setEditingPlan] = useState(null); // null = create mode
    const [form, setForm] = useState(emptyForm);
    const [error, setError] = useState('');
    const [submitting, setSubmitting] = useState(false);

    useEffect(() => { loadPlans(); }, [typeFilter]);

    const loadPlans = async () => {
        setLoading(true);
        try {
            const data = await platformService.getPlans(typeFilter);
            setPlans(data);
        } catch {
            setError('Failed to load plans');
        } finally {
            setLoading(false);
        }
    };

    const openCreate = () => {
        setEditingPlan(null);
        setForm(emptyForm);
        setError('');
        setShowModal(true);
    };

    const openEdit = (plan) => {
        setEditingPlan(plan);
        setForm({
            name: plan.name,
            type: plan.type,
            monthlyPrice: plan.monthlyPrice,
            yearlyPrice: plan.yearlyPrice,
            modules: plan.modules || [],
            features: (plan.features || []).join('\n'),
            inClinic: plan.inClinic || false,
        });
        setError('');
        setShowModal(true);
    };

    const handleModuleToggle = (mod) => {
        setForm(prev => ({
            ...prev,
            modules: prev.modules.includes(mod)
                ? prev.modules.filter(m => m !== mod)
                : [...prev.modules, mod],
        }));
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        if (!form.name.trim()) { setError('Plan name is required'); return; }
        if (!form.monthlyPrice || !form.yearlyPrice) { setError('Both prices are required'); return; }

        setSubmitting(true);
        setError('');
        const payload = {
            name: form.name.trim(),
            type: form.type,
            monthlyPrice: parseFloat(form.monthlyPrice),
            yearlyPrice: parseFloat(form.yearlyPrice),
            modules: form.modules,
            features: form.features.split('\n').map(f => f.trim()).filter(Boolean),
            inClinic: form.inClinic,
        };

        try {
            if (editingPlan) {
                await platformService.updatePlan(editingPlan.publicId, payload);
                success('Plan updated successfully');
            } else {
                await platformService.createPlan(payload);
                success('Plan created successfully');
            }
            setShowModal(false);
            loadPlans();
        } catch (err) {
            setError(err.response?.data || 'Failed to save plan');
        } finally {
            setSubmitting(false);
        }
    };

    const handleDelete = async (plan) => {
        if (!window.confirm(`Delete plan "${plan.name}"? This cannot be undone.`)) return;
        try {
            await platformService.deletePlan(plan.publicId);
            success('Plan deleted');
            loadPlans();
        } catch (err) {
            setError(err.response?.data || 'Failed to delete plan');
        }
    };

    const typeBadge = (type) => {
        const colors = {
            HOSPITAL: 'bg-blue-100 text-blue-700',
            CLINIC: 'bg-green-100 text-green-700',
            PHARMACY: 'bg-purple-100 text-purple-700',
        };
        return (
            <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${colors[type] || 'bg-gray-100 text-gray-600'}`}>
                {type}
            </span>
        );
    };

    return (
        <div>
            {error && (
                <div className="mb-4 p-3 bg-red-50 border border-red-200 text-red-700 text-sm rounded">
                    {error}
                </div>
            )}

            {/* Toolbar */}
            <div className="flex items-center justify-between mb-4">
                <div className="flex gap-2">
                    {['', ...ENTITY_TYPES].map(t => (
                        <button
                            key={t}
                            onClick={() => setTypeFilter(t)}
                            className={`px-3 py-1.5 text-sm font-medium rounded-lg border transition-colors ${
                                typeFilter === t
                                    ? 'bg-gray-900 text-white border-gray-900'
                                    : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50'
                            }`}
                        >
                            {t || 'All'}
                        </button>
                    ))}
                </div>
                <button
                    onClick={openCreate}
                    className="px-4 py-2 bg-gray-900 text-white text-sm font-medium hover:bg-gray-700 transition-colors"
                >
                    + Create Plan
                </button>
            </div>

            {/* Plans Table */}
            {loading ? (
                <div className="text-center py-12 text-gray-500">Loading plans...</div>
            ) : plans.length === 0 ? (
                <div className="text-center py-12 text-gray-500">No plans found. Create one to get started.</div>
            ) : (
                <div className="bg-white border border-gray-200 overflow-x-auto">
                    <table className="min-w-full">
                        <thead className="bg-gray-50">
                            <tr>
                                <th className="px-4 py-3 text-left text-xs font-medium text-gray-600 uppercase">Name</th>
                                <th className="px-4 py-3 text-left text-xs font-medium text-gray-600 uppercase">Type</th>
                                <th className="px-4 py-3 text-left text-xs font-medium text-gray-600 uppercase">Monthly ₹</th>
                                <th className="px-4 py-3 text-left text-xs font-medium text-gray-600 uppercase">Yearly ₹</th>
                                <th className="px-4 py-3 text-left text-xs font-medium text-gray-600 uppercase">Modules</th>
                                <th className="px-4 py-3 text-right text-xs font-medium text-gray-600 uppercase">Actions</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-100">
                            {plans.map(plan => (
                                <tr key={plan.publicId} className="hover:bg-gray-50">
                                    <td className="px-4 py-3 text-sm font-medium text-gray-900">{plan.name}</td>
                                    <td className="px-4 py-3">{typeBadge(plan.type)}</td>
                                    <td className="px-4 py-3 text-sm text-gray-700">₹{plan.monthlyPrice}</td>
                                    <td className="px-4 py-3 text-sm text-gray-700">₹{plan.yearlyPrice}</td>
                                    <td className="px-4 py-3 text-sm text-gray-600">
                                        {(plan.modules || []).join(', ') || '—'}
                                    </td>
                                    <td className="px-4 py-3 text-right">
                                        <button
                                            onClick={() => openEdit(plan)}
                                            className="mr-2 px-3 py-1 text-xs font-medium text-blue-600 border border-blue-200 rounded hover:bg-blue-50"
                                        >
                                            Edit
                                        </button>
                                        <button
                                            onClick={() => handleDelete(plan)}
                                            className="px-3 py-1 text-xs font-medium text-red-600 border border-red-200 rounded hover:bg-red-50"
                                        >
                                            Delete
                                        </button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            {/* Create / Edit Modal */}
            {showModal && (
                <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
                    <div className="bg-white w-full max-w-lg max-h-[90vh] overflow-y-auto rounded-xl shadow-xl">
                        <div className="flex items-center justify-between px-6 py-4 border-b">
                            <h3 className="text-lg font-bold text-gray-900">
                                {editingPlan ? 'Edit Plan' : 'Create Plan'}
                            </h3>
                            <button onClick={() => setShowModal(false)} className="text-gray-400 hover:text-gray-600 text-xl">×</button>
                        </div>
                        <form onSubmit={handleSubmit} className="p-6 space-y-4">
                            {error && <p className="text-sm text-red-600">{error}</p>}

                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-1">Plan Name *</label>
                                <input
                                    value={form.name}
                                    onChange={e => setForm(p => ({ ...p, name: e.target.value }))}
                                    className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-gray-900"
                                    placeholder="e.g. Clinic Essential"
                                />
                            </div>

                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-1">Type *</label>
                                <select
                                    value={form.type}
                                    onChange={e => setForm(p => ({ ...p, type: e.target.value }))}
                                    disabled={!!editingPlan}
                                    className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-gray-900 disabled:bg-gray-50"
                                >
                                    {ENTITY_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                                </select>
                                {editingPlan && <p className="text-xs text-gray-500 mt-1">Type cannot be changed after creation.</p>}
                            </div>

                            <div className="grid grid-cols-2 gap-4">
                                <div>
                                    <label className="block text-sm font-medium text-gray-700 mb-1">Monthly Price (₹) *</label>
                                    <input
                                        type="number" min="0" step="0.01"
                                        value={form.monthlyPrice}
                                        onChange={e => setForm(p => ({ ...p, monthlyPrice: e.target.value }))}
                                        className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-gray-900"
                                    />
                                </div>
                                <div>
                                    <label className="block text-sm font-medium text-gray-700 mb-1">Yearly Price (₹) *</label>
                                    <input
                                        type="number" min="0" step="0.01"
                                        value={form.yearlyPrice}
                                        onChange={e => setForm(p => ({ ...p, yearlyPrice: e.target.value }))}
                                        className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-gray-900"
                                    />
                                </div>
                            </div>

                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-2">Enabled Modules</label>
                                <div className="flex flex-wrap gap-2">
                                    {AVAILABLE_MODULES.map(mod => (
                                        <button
                                            type="button"
                                            key={mod}
                                            onClick={() => handleModuleToggle(mod)}
                                            className={`px-3 py-1 text-xs font-medium rounded-full border transition-colors ${
                                                form.modules.includes(mod)
                                                    ? 'bg-gray-900 text-white border-gray-900'
                                                    : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'
                                            }`}
                                        >
                                            {mod}
                                        </button>
                                    ))}
                                </div>
                            </div>

                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-1">
                                    In-Clinic Medicine
                                </label>
                                <label className="flex items-center gap-2 cursor-pointer">
                                    <input
                                        type="checkbox"
                                        checked={form.inClinic}
                                        onChange={e => setForm(p => ({ ...p, inClinic: e.target.checked }))}
                                        className="rounded"
                                    />
                                    <span className="text-sm text-gray-700">Enable in-clinic medicine option for this plan</span>
                                </label>
                            </div>

                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-1">
                                    Feature Labels (one per line, for display only)
                                </label>
                                <textarea
                                    value={form.features}
                                    onChange={e => setForm(p => ({ ...p, features: e.target.value }))}
                                    rows={5}
                                    className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-gray-900"
                                    placeholder={"OPD Management\nDigital Prescription\nGST Billing"}
                                />
                            </div>

                            <div className="flex justify-end gap-3 pt-2">
                                <button
                                    type="button"
                                    onClick={() => setShowModal(false)}
                                    className="px-4 py-2 text-sm font-medium text-gray-700 border border-gray-300 rounded-lg hover:bg-gray-50"
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting}
                                    className="px-4 py-2 text-sm font-medium text-white bg-gray-900 rounded-lg hover:bg-gray-700 disabled:opacity-50"
                                >
                                    {submitting ? 'Saving...' : editingPlan ? 'Update Plan' : 'Create Plan'}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    );
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/components/PlansTab.jsx
git commit -m "feat: add PlansTab component with CRUD for plan management"
```

---

## Task 18: Frontend — Update PlatformDashboard

**Files:**
- Modify: `frontend/src/pages/platform/PlatformDashboard.jsx`

This is the largest frontend change. Apply the following modifications:

- [ ] **Step 1: Add import for PlansTab**

At the top of `PlatformDashboard.jsx`, add:
```js
import PlansTab from '../../components/PlansTab';
```

- [ ] **Step 2: Add 'plans' to the tabs array**

Find the `tabs` array (around line 425) and add the Plans tab:
```js
const tabs = [
    { id: 'dashboard', label: 'Dashboard' },
    { id: 'hospitals', label: 'Hospitals' },
    { id: 'plans', label: 'Plans' },          // NEW
    { id: 'audit_logs', label: 'Audit Logs' },
    { id: 'tickets', label: 'Tickets' },
    { id: 'faqs', label: 'FAQs' },
];
```

- [ ] **Step 3: Add entity sub-tab state**

In the state declarations (around line 42), add:
```js
const [entitySubTab, setEntitySubTab] = useState('HOSPITAL'); // HOSPITAL | CLINIC | PHARMACY
```

- [ ] **Step 4: Update `loadHospitals` to use `entitySubTab`**

Replace the `loadHospitals` function:
```js
const loadHospitals = async (page = 0, size = 10, type = entitySubTab) => {
    try {
        setLoading(true);
        const data = await platformService.getHospitals(page, size, type);
        if (data.content) {
            setHospitals(data.content);
            setHospitalPage(data);
        } else {
            setHospitals(data);
            setHospitalPage({ content: data, totalPages: 1, totalElements: data.length, number: 0, size: data.length });
        }
    } catch (err) {
        setError('Failed to load hospitals');
    } finally {
        setLoading(false);
    }
};
```

- [ ] **Step 5: Reload when sub-tab changes**

Add a `useEffect` after the existing ones:
```js
useEffect(() => {
    if (activeTab === 'hospitals') {
        loadHospitals(0, 10, entitySubTab);
    }
}, [entitySubTab]);
```

- [ ] **Step 6: Add plan + billing period state to the create form**

Find `const [formData, setFormData]` (around line 63) and update the initial state:
```js
const [formData, setFormData] = useState({
    hospitalName: '',
    adminName: '',
    adminEmail: '',
    adminPassword: '',
    type: 'HOSPITAL',
    planPublicId: '',
    billingPeriod: 'MONTHLY',
    isSingleDoctor: false,
});
```

- [ ] **Step 7: Add plan list state**

```js
const [availablePlans, setAvailablePlans] = useState([]);
```

- [ ] **Step 8: Load plans when create modal opens**

Replace the `setShowCreateModal(true)` call (inside the PageHeader `onAdd` prop and any other places) with a helper:
```js
const openCreateModal = async () => {
    const type = entitySubTab;
    setFormData(prev => ({ ...prev, type, planPublicId: '', billingPeriod: 'MONTHLY' }));
    try {
        const plans = await platformService.getPlans(type);
        setAvailablePlans(plans.filter(p => p.isActive));
    } catch {
        setAvailablePlans([]);
    }
    setShowCreateModal(true);
};
```

Then replace `() => setShowCreateModal(true)` with `openCreateModal` in the PageHeader `onAdd` prop.

- [ ] **Step 9: Update `handleCreateHospital` to remove modules field**

In `handleCreateHospital`, the `formData` now sends `type`, `planPublicId`, `billingPeriod` instead of `modules`. The validation rules should remove the `modules` check. No other changes needed — `platformService.createHospital(formData)` will send the new fields automatically.

Update the reset after creation:
```js
setFormData({
    hospitalName: '',
    adminName: '',
    adminEmail: '',
    adminPassword: '',
    type: entitySubTab,
    planPublicId: '',
    billingPeriod: 'MONTHLY',
    isSingleDoctor: false,
});
```

- [ ] **Step 10: Update the edit modal state** — remove `plan` and `modules` fields, add subscription display

Find `const [editHospitalModal, setEditHospitalModal]` (around line 86):
```js
const [editHospitalModal, setEditHospitalModal] = useState({
    isOpen: false,
    hospital: null,
    name: '',
    adminEmail: '',
    adminName: '',
    isSingleDoctor: false,
    // subscription info (read-only)
    planName: '',
    billingPeriod: '',
    assignedAt: null,
    expiresAt: null,
    subscriptionStatus: '',
    // for reassign
    newPlanPublicId: '',
    newBillingPeriod: 'MONTHLY',
    availablePlansForEdit: [],
});
```

Update `openEditHospitalModal`:
```js
const openEditHospitalModal = async (hospital) => {
    try {
        const details = await platformService.getHospitalById(hospital.publicId || hospital.id);
        const plans = await platformService.getPlans(details.type || entitySubTab);
        setEditHospitalModal({
            isOpen: true,
            hospital: details,
            name: details.name,
            adminEmail: details.adminEmail || '',
            adminName: details.adminName || '',
            isSingleDoctor: details.isSingleDoctor || false,
            planName: details.planName || '—',
            billingPeriod: details.billingPeriod || '—',
            assignedAt: details.assignedAt,
            expiresAt: details.expiresAt,
            subscriptionStatus: details.subscriptionStatus || 'ACTIVE',
            newPlanPublicId: '',
            newBillingPeriod: 'MONTHLY',
            availablePlansForEdit: plans.filter(p => p.isActive),
        });
    } catch (err) {
        setError('Failed to fetch details');
    }
};
```

- [ ] **Step 11: Update `handleHospitalUpdate` — remove plan/modules calls**

Replace the existing `handleHospitalUpdate` function:
```js
const handleHospitalUpdate = async () => {
    try {
        const hospitalId = editHospitalModal.hospital.publicId || editHospitalModal.hospital.id;

        await platformService.updateHospitalDetails(
            hospitalId,
            editHospitalModal.name,
            editHospitalModal.adminEmail,
            editHospitalModal.adminName,
            '',
            editHospitalModal.isSingleDoctor
        );

        // Reassign plan if a new one is selected
        if (editHospitalModal.newPlanPublicId) {
            await platformService.assignPlan(
                editHospitalModal.newPlanPublicId,
                hospitalId,
                editHospitalModal.newBillingPeriod
            );
        }

        success('Updated successfully');
        setEditHospitalModal(prev => ({ ...prev, isOpen: false }));
        loadHospitals(hospitalPage.number, hospitalPage.size, entitySubTab);
    } catch (err) {
        setError(err.response?.data || 'Failed to update');
    }
};
```

- [ ] **Step 12: Add entity sub-tabs to the Hospitals tab JSX**

Find the section where `{activeTab === 'hospitals' && (` renders the hospitals content. At the very top of that section (before the table or HospitalsTable component), add:

```jsx
{/* Entity Type Sub-tabs */}
<div className="flex gap-1 mb-4 border-b border-gray-200">
    {['HOSPITAL', 'CLINIC', 'PHARMACY'].map(t => (
        <button
            key={t}
            onClick={() => setEntitySubTab(t)}
            className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                entitySubTab === t
                    ? 'border-gray-900 text-gray-900'
                    : 'border-transparent text-gray-500 hover:text-gray-700'
            }`}
        >
            {t === 'HOSPITAL' ? 'Hospitals' : t === 'CLINIC' ? 'Clinics' : 'Pharmacies'}
        </button>
    ))}
</div>
```

- [ ] **Step 13: Update the create modal JSX** — replace modules checkboxes with Plan + Billing Period

Find the create modal form in the JSX. Replace the `Enabled Modules` checkboxes section and the old plan dropdown with:

```jsx
{/* Plan Selection */}
<div>
    <label className="block text-sm font-medium text-gray-700 mb-1">Plan *</label>
    <select
        value={formData.planPublicId}
        onChange={e => setFormData(p => ({ ...p, planPublicId: e.target.value }))}
        className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-gray-900"
    >
        <option value="">-- Select a plan --</option>
        {availablePlans.map(p => (
            <option key={p.publicId} value={p.publicId}>
                {p.name} — ₹{formData.billingPeriod === 'MONTHLY' ? p.monthlyPrice : p.yearlyPrice}
            </option>
        ))}
    </select>
    {availablePlans.length === 0 && (
        <p className="text-xs text-amber-600 mt-1">No plans found for {formData.type}. Create one in the Plans tab first.</p>
    )}
</div>

{/* Billing Period */}
<div>
    <label className="block text-sm font-medium text-gray-700 mb-1">Billing Period *</label>
    <div className="flex gap-3">
        {['MONTHLY', 'YEARLY'].map(period => (
            <label key={period} className="flex items-center gap-2 cursor-pointer">
                <input
                    type="radio"
                    name="billingPeriod"
                    value={period}
                    checked={formData.billingPeriod === period}
                    onChange={() => setFormData(p => ({ ...p, billingPeriod: period }))}
                />
                <span className="text-sm text-gray-700">{period === 'MONTHLY' ? 'Monthly' : 'Yearly'}</span>
            </label>
        ))}
    </div>
</div>
```

- [ ] **Step 14: Update the edit modal JSX** — show subscription info (read-only) + reassign section

In the edit modal form, replace the old Plan dropdown and Modules checkboxes with:

```jsx
{/* Current Subscription (read-only) */}
<div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
    <h4 className="text-sm font-semibold text-gray-700 mb-2">Current Subscription</h4>
    <div className="grid grid-cols-2 gap-2 text-sm">
        <div><span className="text-gray-500">Plan:</span> <span className="font-medium">{editHospitalModal.planName}</span></div>
        <div><span className="text-gray-500">Period:</span> <span className="font-medium">{editHospitalModal.billingPeriod}</span></div>
        <div><span className="text-gray-500">Assigned:</span> <span className="font-medium">{editHospitalModal.assignedAt ? new Date(editHospitalModal.assignedAt).toLocaleDateString('en-IN') : '—'}</span></div>
        <div><span className="text-gray-500">Expires:</span> <span className="font-medium">{editHospitalModal.expiresAt ? new Date(editHospitalModal.expiresAt).toLocaleDateString('en-IN') : '—'}</span></div>
    </div>
    {editHospitalModal.subscriptionStatus === 'WARNING' && (
        <p className="mt-2 text-xs text-amber-600 font-medium">⚠ Plan expires within 7 days</p>
    )}
    {editHospitalModal.subscriptionStatus === 'EXPIRED' && (
        <p className="mt-2 text-xs text-red-600 font-medium">✕ Plan expired — entity is locked</p>
    )}
</div>

{/* Reassign Plan (optional) */}
<div>
    <label className="block text-sm font-medium text-gray-700 mb-1">Reassign Plan (optional)</label>
    <select
        value={editHospitalModal.newPlanPublicId}
        onChange={e => setEditHospitalModal(p => ({ ...p, newPlanPublicId: e.target.value }))}
        className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-gray-900"
    >
        <option value="">-- Keep current plan --</option>
        {editHospitalModal.availablePlansForEdit.map(p => (
            <option key={p.publicId} value={p.publicId}>{p.name}</option>
        ))}
    </select>
    {editHospitalModal.newPlanPublicId && (
        <div className="flex gap-3 mt-2">
            {['MONTHLY', 'YEARLY'].map(period => (
                <label key={period} className="flex items-center gap-2 cursor-pointer">
                    <input
                        type="radio"
                        name="newBillingPeriod"
                        value={period}
                        checked={editHospitalModal.newBillingPeriod === period}
                        onChange={() => setEditHospitalModal(p => ({ ...p, newBillingPeriod: period }))}
                    />
                    <span className="text-sm text-gray-700">{period === 'MONTHLY' ? 'Monthly' : 'Yearly'}</span>
                </label>
            ))}
        </div>
    )}
</div>
```

- [ ] **Step 15: Add Plans tab content to the JSX render section**

Find where the other tabs are rendered (e.g., `{activeTab === 'audit_logs' && ...}`). Add before that block:

```jsx
{/* Plans Tab */}
{activeTab === 'plans' && (
    <PlansTab />
)}
```

- [ ] **Step 16: Update the Hospitals tab table to show `type` column**

In the `HospitalsTable` component (at the bottom of `PlatformDashboard.jsx`), find the column definitions and add a `Type` column after the `ID` column:

```js
columnHelper.accessor('type', {
    header: 'Type',
    cell: info => {
        const type = info.getValue() || 'HOSPITAL';
        const colors = {
            HOSPITAL: 'bg-blue-100 text-blue-700',
            CLINIC: 'bg-green-100 text-green-700',
            PHARMACY: 'bg-purple-100 text-purple-700',
        };
        return (
            <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${colors[type] || 'bg-gray-100'}`}>
                {type}
            </span>
        );
    },
}),
```

- [ ] **Step 17: Verify the build**

```bash
cd frontend && npm run build
```

Expected: Build succeeds with no errors. If TypeScript/prop errors appear, fix them before proceeding.

- [ ] **Step 18: Start dev server and test manually**

```bash
cd frontend && npm run dev
```

Run through this checklist in the browser:
- [ ] Log in as Super Admin
- [ ] Plans tab appears in sidebar
- [ ] Can create a HOSPITAL plan with modules OPD + BILLING
- [ ] Can create a CLINIC plan with modules OPD + BILLING + in-clinic enabled
- [ ] Can create a PHARMACY plan with modules PHARMACY + BILLING
- [ ] Deleting a plan with no subscribers works
- [ ] Deleting a plan with active subscribers shows error message
- [ ] Hospitals tab shows three sub-tabs (Hospitals / Clinics / Pharmacies)
- [ ] Create modal shows Plan dropdown filtered by active sub-tab type
- [ ] Edit modal shows current subscription info (read-only)
- [ ] Edit modal allows plan reassignment

- [ ] **Step 19: Commit**

```bash
git add frontend/src/pages/platform/PlatformDashboard.jsx \
        frontend/src/components/PlansTab.jsx \
        frontend/src/services/platformService.js
git commit -m "feat: update PlatformDashboard with Plans tab, entity sub-tabs, and subscription modals"
```

---

## Self-Review Checklist

### 1. Spec Coverage
| Requirement | Task |
|---|---|
| Entity types: Hospital/Clinic/Pharmacy | Tasks 2, 6, 12, 18 |
| Plans tab in Super Admin | Tasks 9, 11, 17, 18 |
| Plan CRUD (create/edit/delete) | Tasks 9, 10, 11, 17 |
| Delete guard (active subscribers) | Tasks 9, 10 |
| Plan type-scoped (HOSPITAL/CLINIC/PHARMACY) | Tasks 3, 9, 11 |
| Monthly + yearly pricing | Tasks 3, 8, 17 |
| Plan auto-sets modules + inClinic | Tasks 9, 12 |
| Plan update propagates to all subscribers | Task 9 |
| Plan assignment at entity creation (required) | Tasks 8, 12, 18 |
| Only Super Admin can change plan | Tasks 11 (PreAuthorize) |
| Subscription table (assignedAt, expiresAt) | Tasks 1, 4, 5 |
| Billing period choice (monthly/yearly) | Tasks 4, 8, 9 |
| Expiry: 7-day warning banner | Tasks 14, 18 |
| Expiry: hard lock after expiry | Tasks 14 |
| Hospital Admin sees subscription info | Tasks 15, and frontend dashboard (show on HOSPITAL_ADMIN dashboard — not covered here; add `GET /hospital/subscription` display to HospitalAdminDashboard separately) |
| `type` column in entity tables | Task 18 (Step 16) |
| Custom ID prefix CLN/PHR | Task 6 |
| Auto-migrate existing records to HOSPITAL | Task 1 (default HOSPITAL) |
| Pharmacy blocks DOCTOR/RECEPTIONIST roles | Partial — validated in Task 12 (`createHospital` sets `isSingleDoctor=false` for PHARMACY); full role-assignment blocking in user management controller is out of scope for this plan |

### 2. Type Consistency
- `HospitalType` enum used consistently in: `Plan.type`, `Hospital.type`, `PlanRepository.findByType`, `HospitalRepository.findByType`, `PlatformPlanService.assignPlan` type-check
- `BillingPeriod` enum used in: `HospitalPlanSubscription.billingPeriod`, `PlatformPlanService.assignPlan`
- `SubscriptionInfoDTO` fields match what `PlatformPlanService.getSubscriptionInfo` sets
- `HospitalDetailsDTO` new fields (`type`, `planName`, `billingPeriod`, `assignedAt`, `expiresAt`, `subscriptionStatus`) match what `PlatformHospitalService.getHospitalDetails` sets

### 3. Known Gaps (flag before starting)
- `HospitalSettingRepository` must have `findByHospital(Hospital)` — verify in Task 9 Step 2
- `UserRepository` must have `findByEmail(String)` — verify in Task 9 Step 2
- Hospital Admin dashboard (`HospitalAdminDashboard.jsx`) subscription banner is not covered here — do as a follow-up task

# Patient Date of Birth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Patient entity's stored `age` (Integer) with a stored `dateOfBirth` (LocalDate), computing age live via a transient getter, so every existing display of age stays correct forever with no manual updates.

**Architecture:** `Patient.getAge()` becomes a computed method (`Period.between(dateOfBirth, LocalDate.now()).getYears()`) instead of a stored field. Because Jackson (JSON) and Thymeleaf (PDF templates) both call getters via normal property access, every existing API response, DTO, and PDF template that reads `patient.getAge()` / `${patient.age}` keeps working unchanged. Only the two places that *collect* age as input (Add/Edit Patient form, inline "new patient during booking" form) and the consultation view (which should now also show DOB) need real changes. `dateOfBirth` is nullable at the DB/entity level — not because it's optional, but to avoid the exact "NOT NULL column with no default fails to insert on a populated table" failure this session already hit twice (`hospital_settings.shift_mode`, `users.is_trainer`); "always required" is enforced in `PatientService`, the same way `phone` already is.

**Tech Stack:** Spring Boot / Java 17 / Hibernate (JPA) / MySQL 8, JUnit 5 + Mockito + AssertJ for backend tests. React / Vite frontend, no test runner configured (manual build + live verification).

---

## Task 1: `Patient` entity — replace `age` with computed-from-`dateOfBirth`

**Files:**
- Modify: `backend/src/main/java/com/hms/entity/Patient.java`
- Test: `backend/src/test/java/com/hms/entity/PatientAgeTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/hms/entity/PatientAgeTest.java`:

```java
package com.hms.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PatientAgeTest {

    @Test
    void getAge_birthdayAlreadyPassedThisYear_returnsFullYears() {
        Patient patient = new Patient();
        // If today is 2026-07-04, someone born 1990-01-15 has already had
        // their birthday this year — age is exactly 2026 - 1990 = 36.
        patient.setDateOfBirth(LocalDate.now().minusYears(36).minusMonths(1));

        assertThat(patient.getAge()).isEqualTo(36);
    }

    @Test
    void getAge_birthdayNotYetReachedThisYear_returnsOneLessThanYearDifference() {
        Patient patient = new Patient();
        // Born 36 years ago minus 1 day means the birthday this year hasn't
        // happened yet — age should be 35, not 36.
        patient.setDateOfBirth(LocalDate.now().minusYears(36).plusDays(1));

        assertThat(patient.getAge()).isEqualTo(35);
    }

    @Test
    void getAge_bornToday_returnsZero() {
        Patient patient = new Patient();
        patient.setDateOfBirth(LocalDate.now());

        assertThat(patient.getAge()).isEqualTo(0);
    }

    @Test
    void getAge_noDateOfBirth_returnsNull() {
        Patient patient = new Patient();

        assertThat(patient.getAge()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=PatientAgeTest -q`
Expected: FAIL (compile error) — `Patient` has no `setDateOfBirth`/`getAge` matching this yet (currently `age` is a plain `Integer` field with no birthday-aware logic).

- [ ] **Step 3: Update the entity**

In `backend/src/main/java/com/hms/entity/Patient.java`:

Replace the imports block at the top (currently `import java.time.LocalDateTime;`) with:

```java
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
```

Replace the `age` field:

```java
    /**
     * Patient's age
     */
    @Column(nullable = false)
    private Integer age;
```

with:

```java
    /**
     * Patient's date of birth. Nullable at the DB/entity level — not because
     * it's optional, but to let Hibernate's ddl-auto=update add this column
     * safely to a table that already has rows (a NOT NULL column with no
     * default fails on populated tables in MySQL strict mode; this project
     * already hit that exact failure twice with orphaned columns). "Always
     * required" is enforced in PatientService.addPatient/updatePatient, the
     * same way phone number already is.
     */
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /**
     * Computed, not stored — age changes every year, so storing it meant it
     * silently went stale until someone manually corrected it. This getter
     * makes age always correct with zero maintenance. Because Jackson (JSON)
     * and Thymeleaf (PDF templates) both call getters via normal property
     * access, every existing place that reads patient.getAge() / ${patient.age}
     * keeps working unchanged, now receiving a live value instead of a stored one.
     */
    public Integer getAge() {
        return dateOfBirth != null ? Period.between(dateOfBirth, LocalDate.now()).getYears() : null;
    }
```

Note: Lombok's `@Data` on this class still generates `getDateOfBirth()`/`setDateOfBirth()` for the new field automatically. Do NOT add a `setAge()` — there is intentionally no way to set age directly anymore.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn test -Dtest=PatientAgeTest -q`
Expected: PASS (4 tests)

- [ ] **Step 5: Verify the whole module still compiles**

Run: `cd backend && mvn -q -o compile`
Expected: no output = success. (This will surface every other file in the codebase that references `Patient.setAge()`/the old `age` field, which we fix in the remaining tasks — if compile fails here, that's expected until Tasks 2–4 are done. Re-run after each subsequent task.)

- [ ] **Step 6: Commit**

```bash
cd e:/Projects/HOSPITAL
git add backend/src/main/java/com/hms/entity/Patient.java backend/src/test/java/com/hms/entity/PatientAgeTest.java
git commit -m "Compute Patient age from dateOfBirth instead of storing it"
```

---

## Task 2: `PatientService` — accept `dateOfBirth` on create/update, with validation

**Files:**
- Modify: `backend/src/main/java/com/hms/service/hospital/PatientService.java`
- Test: `backend/src/test/java/com/hms/service/PatientServiceDateOfBirthTest.java` (new)

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/hms/service/PatientServiceDateOfBirthTest.java`. This follows the existing Mockito pattern in `backend/src/test/java/com/hms/service/PatientServiceConsultationTest.java`:

```java
package com.hms.service;

import com.hms.entity.Patient;
import com.hms.repository.*;
import com.hms.security.SecurityContextHelper;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.service.AuditLogService;
import com.hms.service.hospital.PatientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientServiceDateOfBirthTest {

    @Mock PatientRepository patientRepository;
    @Mock CacheManager cacheManager;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock HospitalWebSocketHandler webSocketHandler;
    @Mock BillingRepository billingRepository;
    @Mock PrescriptionRepository prescriptionRepository;
    @Mock DoctorRepository doctorRepository;
    @Mock MedicalRecordRepository medicalRecordRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock BillingItemRepository billingItemRepository;
    @Mock BillingMedicineRepository billingMedicineRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock BedRepository bedRepository;
    @Mock WardRepository wardRepository;
    @Mock IpdBedHistoryRepository ipdBedHistoryRepository;
    @Mock OpdRepository opdRepository;
    @Mock HospitalRepository hospitalRepository;
    @Mock DischargeSummaryRepository dischargeSummaryRepository;

    @InjectMocks PatientService service;

    private Patient newPatient(LocalDate dob) {
        Patient p = new Patient();
        p.setName("Jane Doe");
        p.setPhone("9876543210");
        p.setGender("FEMALE");
        p.setDateOfBirth(dob);
        return p;
    }

    @Test
    void addPatient_missingDateOfBirth_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        Patient patient = newPatient(null);

        assertThatThrownBy(() -> service.addPatient(patient))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Date of birth is required");
    }

    @Test
    void addPatient_futureDateOfBirth_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        Patient patient = newPatient(LocalDate.now().plusDays(1));

        assertThatThrownBy(() -> service.addPatient(patient))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be in the future");
    }

    @Test
    void addPatient_dateOfBirthOver120YearsAgo_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        Patient patient = newPatient(LocalDate.now().minusYears(121));

        assertThatThrownBy(() -> service.addPatient(patient))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("120 years");
    }

    @Test
    void addPatient_validDateOfBirth_savesPatient() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        Patient patient = newPatient(LocalDate.now().minusYears(30));
        Patient saved = newPatient(LocalDate.now().minusYears(30));
        saved.setId(5L);
        when(patientRepository.save(any(Patient.class))).thenReturn(saved, saved);

        Patient result = service.addPatient(patient);

        assertThat(result.getDateOfBirth()).isEqualTo(LocalDate.now().minusYears(30));
        verify(patientRepository, atLeastOnce()).save(any(Patient.class));
    }

    @Test
    void updatePatient_setsNewDateOfBirth() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        Patient existing = newPatient(LocalDate.now().minusYears(40));
        existing.setId(7L);
        existing.setHospitalId(1L);
        when(patientRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate correctedDob = LocalDate.now().minusYears(41);
        Patient updateData = newPatient(correctedDob);

        Patient result = service.updatePatient(7L, updateData);

        assertThat(result.getDateOfBirth()).isEqualTo(correctedDob);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=PatientServiceDateOfBirthTest -q`
Expected: FAIL — `PatientService` doesn't validate or set `dateOfBirth` yet.

- [ ] **Step 3: Update `PatientService`**

In `backend/src/main/java/com/hms/service/hospital/PatientService.java`, add the import (after the existing `java.util.List` import):

```java
import java.time.LocalDate;
import java.util.List;
```

Add a private validation helper right after the class's `evictStatsCache` method (around line 106, before the `addPatient` method's Javadoc):

```java
    /**
     * "Always required" for dateOfBirth is enforced here rather than at the
     * DB/entity level — see the comment on Patient.dateOfBirth for why.
     */
    private void validateDateOfBirth(LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            throw new IllegalArgumentException("Date of birth is required");
        }
        if (dateOfBirth.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Date of birth cannot be in the future");
        }
        if (dateOfBirth.isBefore(LocalDate.now().minusYears(120))) {
            throw new IllegalArgumentException("Date of birth cannot be more than 120 years ago");
        }
    }
```

In `addPatient` (currently starts with the phone validation), add the DOB check right after the phone check:

```java
    public Patient addPatient(Patient patient) {
        // Validate phone number
        if (patient.getPhone() == null || !patient.getPhone().matches("^[0-9]{10}$")) {
            throw new IllegalArgumentException("Phone number must be exactly 10 digits");
        }
        validateDateOfBirth(patient.getDateOfBirth());
```

(leave the rest of `addPatient` unchanged).

In `updatePatient`, replace:

```java
        existingPatient.setName(updatedData.getName());
        existingPatient.setAge(updatedData.getAge());
        existingPatient.setGender(updatedData.getGender());
```

with:

```java
        validateDateOfBirth(updatedData.getDateOfBirth());

        existingPatient.setName(updatedData.getName());
        existingPatient.setDateOfBirth(updatedData.getDateOfBirth());
        existingPatient.setGender(updatedData.getGender());
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn test -Dtest=PatientServiceDateOfBirthTest -q`
Expected: PASS (5 tests)

- [ ] **Step 5: Run the full existing patient test suite to check nothing else broke**

Run: `cd backend && mvn test -Dtest=PatientServiceConsultationTest,PatientServiceDateOfBirthTest,PatientAgeTest -q`
Expected: PASS (all tests across all three classes)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hms/service/hospital/PatientService.java backend/src/test/java/com/hms/service/PatientServiceDateOfBirthTest.java
git commit -m "Validate and persist dateOfBirth in PatientService"
```

---

## Task 3: `Appointment` entity + `AppointmentService` — inline new-patient flow

**Files:**
- Modify: `backend/src/main/java/com/hms/entity/Appointment.java`
- Modify: `backend/src/main/java/com/hms/service/hospital/AppointmentService.java`
- Test: `backend/src/test/java/com/hms/service/AppointmentServiceNewPatientTest.java` (new)

- [ ] **Step 1: Update the `Appointment` entity**

In `backend/src/main/java/com/hms/entity/Appointment.java`, replace:

```java
    /**
     * Patient age for new patient creation (not stored in appointments table)
     */
    @Transient
    private Integer patientAge;
```

with:

```java
    /**
     * Patient date of birth for new patient creation (not stored in
     * appointments table). See Patient.dateOfBirth for why Patient itself
     * computes age instead of storing it.
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Transient
    private LocalDate patientDateOfBirth;
```

(`LocalDate` and `JsonFormat` are already imported in this file — no import changes needed.)

- [ ] **Step 2: Write the failing test**

`createAppointment` calls, in order: `validateOpdAccess(hospitalId)` (queries `hospitalRepository`, requires the hospital's modules to contain `"OPD"`), patient lookup/creation (`patientRepository`), doctor validation (`doctorRepository`), a time-slot check (`appointmentRepository`), then `appointmentRepository.save(...)`. The audit-log and websocket-broadcast calls after that are wrapped in `try/catch` in the real method, so they're safe to leave unmocked (an NPE from a null collaborator is caught and ignored). Every collaborator in the list above must be mocked, or the call will throw before reaching our assertion.

Create `backend/src/test/java/com/hms/service/AppointmentServiceNewPatientTest.java`:

```java
package com.hms.service;

import com.hms.entity.Appointment;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.Patient;
import com.hms.repository.AppointmentRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.PatientRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.hospital.AppointmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceNewPatientTest {

    @Mock PatientRepository patientRepository;
    @Mock DoctorRepository doctorRepository;
    @Mock HospitalRepository hospitalRepository;
    @Mock AppointmentRepository appointmentRepository;
    @Mock SecurityContextHelper securityHelper;

    @InjectMocks AppointmentService service;

    @Captor ArgumentCaptor<Patient> patientCaptor;

    /** Builds an appointment request that will sail through every check before patient creation. */
    private Appointment newWalkInAppointment(String phone, LocalDate patientDob) {
        Appointment appointment = new Appointment();
        appointment.setPatientName("Walk-in Patient");
        appointment.setPatientPhone(phone);
        appointment.setPatientDateOfBirth(patientDob);
        appointment.setPatientGender("MALE");
        appointment.setDoctorId(1L);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setAppointmentTime(LocalTime.of(10, 0));
        return appointment;
    }

    private void mockCommonCollaborators(String phone) {
        Hospital hospital = new Hospital();
        hospital.setModules(List.of("OPD"));
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(hospitalRepository.findById(1L)).thenReturn(Optional.of(hospital));
        when(patientRepository.findByPhoneAndHospitalIdAndIsActiveTrue(phone, 1L))
                .thenReturn(Collections.emptyList());
        Doctor doctor = new Doctor();
        doctor.setId(1L);
        doctor.setName("Dr. Test");
        when(doctorRepository.findByIdAndHospitalIdAndIsActiveTrue(1L, 1L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findByDoctorIdAndAppointmentDateAndIsActiveTrue(any(), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createAppointment_newPatientWithDob_setsDateOfBirthOnCreatedPatient() {
        mockCommonCollaborators("9876543210");
        Patient saved = new Patient();
        saved.setId(9L);
        when(patientRepository.save(any(Patient.class))).thenReturn(saved);

        Appointment appointment = newWalkInAppointment("9876543210", LocalDate.now().minusYears(25));

        service.createAppointment(appointment);

        verify(patientRepository).save(patientCaptor.capture());
        assertThat(patientCaptor.getValue().getDateOfBirth()).isEqualTo(LocalDate.now().minusYears(25));
    }

    @Test
    void createAppointment_newPatientNoDob_defaultsToToday() {
        mockCommonCollaborators("9876543211");
        Patient saved = new Patient();
        saved.setId(10L);
        when(patientRepository.save(any(Patient.class))).thenReturn(saved);

        Appointment appointment = newWalkInAppointment("9876543211", null);

        service.createAppointment(appointment);

        verify(patientRepository).save(patientCaptor.capture());
        assertThat(patientCaptor.getValue().getDateOfBirth()).isEqualTo(LocalDate.now());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=AppointmentServiceNewPatientTest -q`
Expected: FAIL — compile error (`setPatientDateOfBirth` doesn't exist on `Appointment` yet until Step 1 is in place; if Step 1 is already done, it'll fail because `AppointmentService` still calls `setAge`, not `setDateOfBirth`).

- [ ] **Step 4: Update `AppointmentService`**

In `backend/src/main/java/com/hms/service/hospital/AppointmentService.java`, replace:

```java
            String patientEmail = appointment.getPatientEmail();
            Integer patientAge = appointment.getPatientAge();
            String patientGender = appointment.getPatientGender();
```

with:

```java
            String patientEmail = appointment.getPatientEmail();
            java.time.LocalDate patientDateOfBirth = appointment.getPatientDateOfBirth();
            String patientGender = appointment.getPatientGender();
```

And replace:

```java
                // Set default age if not provided to avoid DB error, though frontend should
                // require it
                newPatient.setAge(patientAge != null ? patientAge : 0);
```

with:

```java
                // Default to today (age 0) if not provided, though frontend should require it
                newPatient.setDateOfBirth(patientDateOfBirth != null ? patientDateOfBirth : java.time.LocalDate.now());
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && mvn test -Dtest=AppointmentServiceNewPatientTest -q`
Expected: PASS (2 tests). If mocking gaps surface (NPEs from unmocked collaborators), add the needed `@Mock`s per the note in Step 2 and re-run.

- [ ] **Step 6: Full backend compile check**

Run: `cd backend && mvn -q -o compile`
Expected: no output = success (all `age`/`setAge`/`getPatientAge` references in main code should now be gone — if compile still fails, grep for remaining references: `grep -rn "\.setAge(\|getPatientAge()\|\.age\b" src/main/java/com/hms/ --include=*.java` and check each hit against the design: only `Patient.getAge()` calls should remain, which is expected and fine).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/hms/entity/Appointment.java backend/src/main/java/com/hms/service/hospital/AppointmentService.java backend/src/test/java/com/hms/service/AppointmentServiceNewPatientTest.java
git commit -m "Collect dateOfBirth instead of age for inline new-patient appointments"
```

---

## Task 4: Database migration + canonical schema update

**Files:**
- Modify: `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`
- Modify: `setup/schema-full.sql`

- [ ] **Step 1: Add the migration method**

In `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`, add the new method call to `runMigrations()`:

```java
    @EventListener(ApplicationReadyEvent.class)
    public void runMigrations() {
        fixHospitalsPlanColumn();
        ensureHospitalSettingsInClinic();
        ensureHospitalsIsSingleDoctor();
        ensureWhatsAppConfigTable();      // NEW
        ensureWhatsAppMessageLogTable();  // NEW
        ensureWhatsAppMessageLogRetryColumns();
        ensureMissingIndexes();
        simplifyMedicineListTable();
        migratePatientAgeToDateOfBirth(); // NEW
    }
```

Add the method itself (after `simplifyMedicineListTable()`, before the closing brace of the class):

```java
    /**
     * Replaces patients.age (stored, goes stale every year) with
     * patients.date_of_birth (computed live by Patient.getAge()).
     *
     * date_of_birth is added and left nullable — NOT promoted to NOT NULL —
     * deliberately. PatientService already enforces "always required" at
     * the application layer, and this project has twice hit real incidents
     * (hospital_settings.shift_mode, users.is_trainer) where a NOT NULL
     * column with no default broke every insert on a populated table. This
     * migration avoids adding a third one.
     */
    private void migratePatientAgeToDateOfBirth() {
        try {
            Integer dobExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'patients' AND COLUMN_NAME = 'date_of_birth'",
                Integer.class
            );
            if (dobExists != null && dobExists == 0) {
                jdbcTemplate.execute("ALTER TABLE patients ADD COLUMN date_of_birth DATE DEFAULT NULL");
                log.info("DB migration applied: patients.date_of_birth column added");
            }

            Integer ageExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'patients' AND COLUMN_NAME = 'age'",
                Integer.class
            );
            if (ageExists != null && ageExists > 0) {
                int updated = jdbcTemplate.update(
                    "UPDATE patients SET date_of_birth = DATE_SUB(CURDATE(), INTERVAL age YEAR) " +
                    "WHERE date_of_birth IS NULL"
                );
                log.info("DB migration applied: backfilled date_of_birth for {} patient(s) from age", updated);

                jdbcTemplate.execute("ALTER TABLE patients DROP COLUMN age");
                log.info("DB migration applied: dropped patients.age column");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (patients.age -> date_of_birth): {}", e.getMessage());
        }
    }
```

- [ ] **Step 2: Update the canonical schema**

In `setup/schema-full.sql`, in the `CREATE TABLE `patients`` block, replace:

```sql
  `age` int NOT NULL,
```

with (keeping the columns alphabetically ordered, matching the existing style — `age` sorted right after `address`, `date_of_birth` sorts after `custom_id` and before `email`):

```sql
  `date_of_birth` date DEFAULT NULL,
```

So the block reads (only the one line changes, everything else stays exactly as-is):

```sql
CREATE TABLE `patients` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `custom_id` varchar(255) DEFAULT NULL,
  `date_of_birth` date DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `gender` varchar(10) NOT NULL,
  `hospital_id` bigint NOT NULL,
  `is_active` bit(1) NOT NULL,
  `medical_history` varchar(1000) DEFAULT NULL,
  `name` varchar(100) NOT NULL,
  `phone` varchar(15) NOT NULL,
  `public_id` varchar(255) NOT NULL,
  `status` enum('REGISTERED','CONSULTING','COMPLETED') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_8isyrjl9ji56k5uv4cgp9p2q6` (`public_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

(This just removes the old `age` line and adds `date_of_birth` — fresh installs from this file never have existing `patients` rows, so there's no backfill needed there; the migration runner above only matters for already-deployed databases.)

- [ ] **Step 3: Compile check**

Run: `cd backend && mvn -q -o compile`
Expected: no output = success.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java setup/schema-full.sql
git commit -m "Add DB migration: patients.age -> patients.date_of_birth"
```

---

## Task 5: Backend end-to-end verification against a real database

This task has no automated test — it verifies the migration and full request/response cycle against the actual dev database, the same way this session has been verifying backend changes throughout.

**Files:** none (verification only)

- [ ] **Step 1: Check current patients table state before migrating**

Run: `mysql -u root -p -D <db_name> -e "DESCRIBE patients;"` and note whether `age` exists and how many rows are in `patients` (`SELECT COUNT(*) FROM patients;`) — this is your "before" baseline to compare against.

- [ ] **Step 2: Restart the backend and watch the migration run**

Stop whatever backend process is currently running (`netstat -ano | grep :8080` to find the PID, then stop it), then start it with output captured to a file so the migration log lines are visible, e.g.:

```bash
cd backend && (mvn -q spring-boot:run > /tmp/dob-migration.log 2>&1 &)
```

Wait for `Started HospitalManagementSystemApplication` in the log, then check for the new migration's log lines:

```bash
grep "date_of_birth\|patients.age" /tmp/dob-migration.log
```

Expected: lines like "DB migration applied: patients.date_of_birth column added", "DB migration applied: backfilled date_of_birth for N patient(s) from age", "DB migration applied: dropped patients.age column". If nothing prints, check for "DB migration skipped (patients.age -> date_of_birth): ..." and read the reason.

- [ ] **Step 3: Verify the schema and data directly**

```bash
mysql -u root -p -D <db_name> -e "DESCRIBE patients;"
```

Expected: `age` column is gone, `date_of_birth` is present (type `date`).

```bash
mysql -u root -p -D <db_name> -e "SELECT id, name, date_of_birth FROM patients;"
```

Expected: every pre-existing patient row has a non-null `date_of_birth` roughly matching their old age (born `CURDATE() - age` years ago).

- [ ] **Step 4: Verify the API returns computed age correctly**

Craft a JWT for a test hospital admin (see any earlier session's `make_jwt.js`-style approach, or use real login credentials) and call the existing patients list endpoint:

```bash
curl -s "http://localhost:8080/hospital/patients?page=0&size=5" -H "Authorization: Bearer <token>"
```

Expected: `200 OK`, each patient object in the response includes both `"age": <number>` (computed) and `"dateOfBirth": "yyyy-MM-dd"` — confirming the computed getter and the new field both serialize correctly with zero frontend changes needed for this endpoint.

- [ ] **Step 5: Verify patient creation with the new field**

```bash
curl -s -X POST "http://localhost:8080/hospital/patients" -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"DOB Test Patient","phone":"9998887776","gender":"MALE","dateOfBirth":"1995-03-20"}'
```

Expected: `200 OK`, response includes `"dateOfBirth":"1995-03-20"` and a correctly computed `"age"` value. Then delete this test patient via the existing delete endpoint or direct SQL so it doesn't pollute real data (see this session's established pattern of cleaning up test records it creates).

- [ ] **Step 6: Verify a PDF that prints age still renders correctly**

Find an existing patient's OPD/case-paper PDF endpoint (`GET /hospital/opd/{opdId}/pdf`) or billing PDF and fetch it:

```bash
curl -s -o /tmp/dob-test-casepaper.pdf "http://localhost:8080/hospital/opd/<opdId>/pdf" -H "Authorization: Bearer <token>"
```

Read the resulting PDF (via the Read tool, which supports PDFs) and confirm the "Age / Gender" line shows a sensible number, not blank or an error — proving `${patient.age}` in `case-paper.html` still works with zero template changes.

- [ ] **Step 7: Verify old prescriptions/bills keep their point-in-time age snapshot**

Before the migration (or from existing data), find a prescription or bill for a patient whose age has since changed relative to when the record was created:

```bash
mysql -u root -p -D <db_name> -e "SELECT id, patient_id, age FROM prescriptions ORDER BY id DESC LIMIT 5;"
```

Note one `(id, age)` pair. Re-run the same query after the migration has completed (Step 2 above) and confirm the `age` value on that same prescription row is unchanged — it must NOT be recomputed from the patient's current `date_of_birth`. This confirms `PharmacyController`'s snapshot behavior (documented in the design spec's "Prescription/billing age snapshot" section) is preserved: the stored integer on `prescriptions`/`billing` rows is untouched by this migration, since the migration only alters the `patients` table.

---

## Task 6: Frontend — add a `dob` validator

**Files:**
- Modify: `frontend/src/utils/validation.js`

- [ ] **Step 1: Add the validator**

In `frontend/src/utils/validation.js`, add this entry to the `validators` object, right after the existing `age` validator (leave `age` in place — it's a generic utility, not specific to Patient forms, and other code may still reference it):

```javascript
    dob: (value) => {
        if (!value) return null;
        const date = new Date(value + 'T00:00:00');
        if (isNaN(date.getTime())) return "Invalid date of birth";
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        if (date > today) return "Date of birth cannot be in the future";
        const minDate = new Date(today);
        minDate.setFullYear(minDate.getFullYear() - 120);
        if (date < minDate) return "Date of birth cannot be more than 120 years ago";
        return null;
    },
```

- [ ] **Step 2: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/utils/validation.js
git commit -m "Add dob validator for patient date-of-birth forms"
```

---

## Task 7: Frontend — `PatientModal.jsx` (Add/Edit Patient)

**Files:**
- Modify: `frontend/src/components/PatientModal.jsx`

- [ ] **Step 1: Update the validation rules**

In `handleSubmit`, replace:

```javascript
        const rules = {
            name: ['required', 'name'],
            age: ['required', 'age'],
            gender: ['required'],
            phone: ['required', 'phone'],
            email: ['email'] // optional but valid if present
        };
```

with:

```javascript
        const rules = {
            name: ['required', 'name'],
            dateOfBirth: ['required', 'dob'],
            gender: ['required'],
            phone: ['required', 'phone'],
            email: ['email'] // optional but valid if present
        };
```

- [ ] **Step 2: Replace the Age input with a Date of Birth input**

Replace the entire "Row: Age + Gender" block:

```jsx
                    {/* Row: Age + Gender */}
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
                        <div>
                            <label className="block text-sm font-semibold text-neutral-700 mb-2">
                                Age <span className="text-red-600">*</span>
                            </label>
                            <input
                                type="number"
                                min="0"
                                max="120"
                                value={formData.age || ''}
                                onChange={(e) => handleChange('age', e.target.value)}
                                className={`input-field ${errors.age ? 'border-error-300 focus:ring-error-500' : ''}`}
                                placeholder="Age"
                            />
                            {errors.age && <p className="text-red-600 text-sm mt-1 flex items-center gap-1">
                                {errors.age}
                            </p>}
                        </div>
                        <div>
                            <label className="block text-sm font-semibold text-neutral-700 mb-2">
                                Gender <span className="text-red-600">*</span>
                            </label>
                            <select
                                value={formData.gender || ''}
                                onChange={(e) => handleChange('gender', e.target.value)}
                                className={`input-field ${errors.gender ? 'border-error-300 focus:ring-error-500' : ''}`}
                            >
                                <option value="">Select gender</option>
                                <option value="MALE">Male</option>
                                <option value="FEMALE">Female</option>
                                <option value="OTHER">Other</option>
                            </select>
                            {errors.gender && <p className="text-red-600 text-sm mt-1 flex items-center gap-1">
                                {errors.gender}
                            </p>}
                        </div>
                    </div>
```

with:

```jsx
                    {/* Row: Date of Birth + Gender */}
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
                        <div>
                            <label className="block text-sm font-semibold text-neutral-700 mb-2">
                                Date of Birth <span className="text-red-600">*</span>
                            </label>
                            <input
                                type="date"
                                max={new Date().toISOString().split('T')[0]}
                                value={formData.dateOfBirth || ''}
                                onChange={(e) => handleChange('dateOfBirth', e.target.value)}
                                className={`input-field ${errors.dateOfBirth ? 'border-error-300 focus:ring-error-500' : ''}`}
                            />
                            {formData.dateOfBirth && !errors.dateOfBirth && (
                                <p className="text-neutral-500 text-xs mt-1">
                                    Age: {Math.max(0, new Date().getFullYear() - new Date(formData.dateOfBirth + 'T00:00:00').getFullYear() - (
                                        (new Date().getMonth() < new Date(formData.dateOfBirth + 'T00:00:00').getMonth() ||
                                            (new Date().getMonth() === new Date(formData.dateOfBirth + 'T00:00:00').getMonth() && new Date().getDate() < new Date(formData.dateOfBirth + 'T00:00:00').getDate()))
                                            ? 1 : 0
                                    ))} years
                                </p>
                            )}
                            {errors.dateOfBirth && <p className="text-red-600 text-sm mt-1 flex items-center gap-1">
                                {errors.dateOfBirth}
                            </p>}
                        </div>
                        <div>
                            <label className="block text-sm font-semibold text-neutral-700 mb-2">
                                Gender <span className="text-red-600">*</span>
                            </label>
                            <select
                                value={formData.gender || ''}
                                onChange={(e) => handleChange('gender', e.target.value)}
                                className={`input-field ${errors.gender ? 'border-error-300 focus:ring-error-500' : ''}`}
                            >
                                <option value="">Select gender</option>
                                <option value="MALE">Male</option>
                                <option value="FEMALE">Female</option>
                                <option value="OTHER">Other</option>
                            </select>
                            {errors.gender && <p className="text-red-600 text-sm mt-1 flex items-center gap-1">
                                {errors.gender}
                            </p>}
                        </div>
                    </div>
```

Note: the age preview math intentionally mirrors `Patient.getAge()`'s `Period.between(...).getYears()` semantics (subtract 1 if this year's birthday hasn't happened yet) — it's a client-side cosmetic preview only; the backend's computed `getAge()` remains the actual source of truth returned by every API response.

- [ ] **Step 3: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 4: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/PatientModal.jsx
git commit -m "Collect date of birth instead of age in Add/Edit Patient form"
```

---

## Task 8: Frontend — `AppointmentModal.jsx` (inline new-patient toggle)

**Files:**
- Modify: `frontend/src/components/AppointmentModal.jsx`

- [ ] **Step 1: Update the validation rules**

Replace:

```javascript
            Object.assign(rules, {
                patientName: ['required', 'name'],
                patientPhone: ['required', 'phone'],
                patientAge: ['required', 'age'],
                patientGender: ['required']
            });
```

with:

```javascript
            Object.assign(rules, {
                patientName: ['required', 'name'],
                patientPhone: ['required', 'phone'],
                patientDateOfBirth: ['required', 'dob'],
                patientGender: ['required']
            });
```

- [ ] **Step 2: Replace the Age input with a Date of Birth input**

Replace:

```jsx
                                <div>
                                    <label className="block text-sm font-medium text-gray-700 mb-1" htmlFor="patient-age">Age *</label>
                                    <input
                                        id="patient-age"
                                        type="number"
                                        min="0"
                                        max="120"
                                        placeholder="Age"
                                        value={formData.patientAge || ''}
                                        onChange={(e) => handleChange('patientAge', e.target.value)}
                                        className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.patientAge ? 'border-red-500' : 'border-gray-300'}`}
                                    />
                                    {errors.patientAge && <p className="text-red-500 text-xs mt-1">{errors.patientAge}</p>}
                                </div>
```

with:

```jsx
                                <div>
                                    <label className="block text-sm font-medium text-gray-700 mb-1" htmlFor="patient-dob">Date of Birth *</label>
                                    <input
                                        id="patient-dob"
                                        type="date"
                                        max={new Date().toISOString().split('T')[0]}
                                        value={formData.patientDateOfBirth || ''}
                                        onChange={(e) => handleChange('patientDateOfBirth', e.target.value)}
                                        className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.patientDateOfBirth ? 'border-red-500' : 'border-gray-300'}`}
                                    />
                                    {errors.patientDateOfBirth && <p className="text-red-500 text-xs mt-1">{errors.patientDateOfBirth}</p>}
                                </div>
```

- [ ] **Step 3: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 4: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/AppointmentModal.jsx
git commit -m "Collect date of birth instead of age for inline new-patient appointments"
```

---

## Task 9: Frontend — `ConsultationModal.jsx` shows both age and DOB

**Files:**
- Modify: `frontend/src/components/ConsultationModal.jsx`

- [ ] **Step 1: Add the DOB row**

Replace:

```jsx
                                        <div className="flex justify-between py-2 border-b border-gray-200">
                                            <span className="text-gray-500">Age</span>
                                            <span className="font-semibold text-gray-800">{patientDetails.patient.age} years</span>
                                        </div>
```

with:

```jsx
                                        <div className="flex justify-between py-2 border-b border-gray-200">
                                            <span className="text-gray-500">Age</span>
                                            <span className="font-semibold text-gray-800">{patientDetails.patient.age} years</span>
                                        </div>
                                        <div className="flex justify-between py-2 border-b border-gray-200">
                                            <span className="text-gray-500">Date of Birth</span>
                                            <span className="font-semibold text-gray-800">
                                                {patientDetails.patient.dateOfBirth
                                                    ? new Date(patientDetails.patient.dateOfBirth + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
                                                    : '—'}
                                            </span>
                                        </div>
```

- [ ] **Step 2: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 3: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/ConsultationModal.jsx
git commit -m "Show date of birth alongside age in consultation view"
```

---

## Task 10: Full-stack live verification

This project's established verification method throughout this session: restart both servers cleanly, then drive the real UI with Playwright (headless Chromium) and inspect screenshots, since there's no frontend test runner configured.

**Files:** none (verification only)

- [ ] **Step 1: Restart backend cleanly**

Find and stop the current backend process (`netstat -ano | grep :8080`, then stop that PID), then:

```bash
cd backend && (mvn -q spring-boot:run > /tmp/dob-final.log 2>&1 &)
```

Wait for `Started HospitalManagementSystemApplication` in `/tmp/dob-final.log`.

- [ ] **Step 2: Restart frontend cleanly**

Find and stop the current frontend dev server (`netstat -ano | grep :5173`, then stop that PID), then:

```bash
cd frontend && (npm run dev > /tmp/dob-frontend.log 2>&1 &)
```

Wait for `Local:   http://localhost:5173/` in `/tmp/dob-frontend.log`.

- [ ] **Step 3: Drive the Add Patient flow through the real browser**

Using a Playwright script following this session's established pattern (craft a JWT for a HOSPITAL_ADMIN test user with the same secret used throughout this session, inject it into `sessionStorage`, navigate to `/hospital/admin?tab=patients`, click "Add Patient"):

- Fill in Name, Phone, Date of Birth (pick a date, e.g. 15 years ago), Gender.
- Screenshot the form before submit — confirm it shows "Date of Birth" (not "Age"), and the live age preview shows "Age: 15 years" (or whatever matches the picked date).
- Submit, and screenshot the resulting patient table row — confirm the Age column shows the correct computed value.
- Clean up: delete the test patient created (via the existing delete flow or direct SQL), matching this session's established cleanup discipline.

- [ ] **Step 4: Drive the inline new-patient appointment flow**

In the same or a new Playwright session, open the Appointments tab, click "Add Appointment", toggle "New Patient", and confirm the form shows a Date of Birth date picker (not an Age number input). Fill it in and cancel (don't need to actually submit — Task 3's automated test already covers the backend logic; this step only verifies the UI renders correctly).

- [ ] **Step 5: Verify the Consultation view shows both fields**

Navigate to a doctor's Consultation view for an existing patient (or the one created in Step 3 before cleanup) and screenshot it — confirm both "Age" and "Date of Birth" rows are visible with sensible values.

- [ ] **Step 6: Final full build check on both stacks**

```bash
cd backend && mvn -q -o compile
cd frontend && npx tsc --noEmit && npx vite build --mode development
```

Expected: both succeed with no errors.

- [ ] **Step 7: Run full backend test suite one more time**

```bash
cd backend && mvn test -q
```

Expected: all tests pass, including the new `PatientAgeTest`, `PatientServiceDateOfBirthTest`, and `AppointmentServiceNewPatientTest`, plus every pre-existing test (confirms nothing else in the suite broke from the `age` → `dateOfBirth` change).

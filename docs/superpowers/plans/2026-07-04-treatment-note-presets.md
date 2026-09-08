# Treatment Note Quick-Presets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let doctors insert common Treatment Notes phrases (e.g. "Avoid oily food") with one click instead of typing them, via a per-hospital-configurable list of quick-note presets.

**Architecture:** A new hospital-scoped entity `ConsultationNotePreset` (table `consultation_note_presets`) stores preset phrases per hospital, tagged with a `fieldType` (only `TREATMENT_NOTES` is used today; the column exists so Diagnosis presets can be added later without a schema change). A thin Controller + Service + Repository trio (mirroring the existing `HospitalFeeController`/`HospitalFeeRepository` CRUD pattern, but with a proper Service layer for testability, mirroring `PlatformMedicineController`/`MedicineService`) exposes list/create/update/delete under `/hospital/consultation-note-presets`, gated to `HOSPITAL_ADMIN` and `DOCTOR` roles. On the frontend, a small chip row appears under the Treatment Notes field in `ConsultationModal.jsx` — clicking a chip appends its text to the notes textarea. A reusable `NotePresetsManager.jsx` component (list + add/edit/delete/reorder) is shown two ways: wrapped in a modal from a "Manage" link next to the chips, and as a full page under a new "Quick Notes" tab in Hospital Admin's existing "Administration" sidebar group.

**Tech Stack:** Spring Boot / Java 17 / Hibernate (JPA) / MySQL 8, JUnit 5 + Mockito + AssertJ + `@WebMvcTest`/MockMvc for backend tests. React / Vite frontend, no test runner configured (manual build + live verification).

---

## Task 1: `ConsultationNotePreset` entity + repository

**Files:**
- Create: `backend/src/main/java/com/hms/entity/ConsultationNotePreset.java`
- Create: `backend/src/main/java/com/hms/repository/ConsultationNotePresetRepository.java`

- [ ] **Step 1: Create the entity**

Create `backend/src/main/java/com/hms/entity/ConsultationNotePreset.java`:

```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * A per-hospital, reusable phrase a doctor can insert with one click instead
 * of typing (e.g. "Avoid oily food" into Treatment Notes). fieldType is
 * reserved for future reuse (e.g. "DIAGNOSIS") — only "TREATMENT_NOTES" is
 * used today.
 */
@Entity
@Table(name = "consultation_note_presets")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationNotePreset {

    public static final String FIELD_TYPE_TREATMENT_NOTES = "TREATMENT_NOTES";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "field_type", nullable = false, length = 30)
    private String fieldType;

    @Column(name = "text", nullable = false, length = 255)
    private String text;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: Create the repository**

Create `backend/src/main/java/com/hms/repository/ConsultationNotePresetRepository.java`:

```java
package com.hms.repository;

import com.hms.entity.ConsultationNotePreset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConsultationNotePresetRepository extends JpaRepository<ConsultationNotePreset, Long> {
    List<ConsultationNotePreset> findByHospitalIdAndFieldTypeAndIsActiveTrueOrderByDisplayOrderAsc(Long hospitalId, String fieldType);
    Optional<ConsultationNotePreset> findByIdAndHospitalId(Long id, Long hospitalId);
}
```

- [ ] **Step 3: Compile check**

Run: `cd backend && mvn -q -o compile`
Expected: no output = success.

- [ ] **Step 4: Commit**

```bash
cd e:/Projects/HOSPITAL
git add backend/src/main/java/com/hms/entity/ConsultationNotePreset.java backend/src/main/java/com/hms/repository/ConsultationNotePresetRepository.java
git commit -m "Add ConsultationNotePreset entity and repository"
```

Stage ONLY these two files — do not run `git add -A` or `git add .`. There may be unrelated uncommitted changes in the working tree from other work; leave them untouched.

---

## Task 2: `ConsultationNotePresetService` with tests

**Files:**
- Create: `backend/src/main/java/com/hms/dto/ConsultationNotePresetDTO.java`
- Create: `backend/src/main/java/com/hms/service/hospital/ConsultationNotePresetService.java`
- Test: `backend/src/test/java/com/hms/service/hospital/ConsultationNotePresetServiceTest.java` (new)

- [ ] **Step 1: Create the DTO**

Create `backend/src/main/java/com/hms/dto/ConsultationNotePresetDTO.java`:

```java
package com.hms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationNotePresetDTO {
    private Long id;
    private String fieldType;
    private String text;
    private Integer displayOrder;
}
```

- [ ] **Step 2: Write the failing tests**

Create `backend/src/test/java/com/hms/service/hospital/ConsultationNotePresetServiceTest.java`:

```java
package com.hms.service.hospital;

import com.hms.entity.ConsultationNotePreset;
import com.hms.repository.ConsultationNotePresetRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ConsultationNotePresetServiceTest {

    @Mock ConsultationNotePresetRepository presetRepository;
    @Mock SecurityContextHelper securityHelper;

    @InjectMocks ConsultationNotePresetService service;

    @Test
    void listPresets_returnsHospitalScopedActivePresetsInOrder() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        ConsultationNotePreset p1 = new ConsultationNotePreset();
        p1.setText("Avoid oily food");
        when(presetRepository.findByHospitalIdAndFieldTypeAndIsActiveTrueOrderByDisplayOrderAsc(1L, "TREATMENT_NOTES"))
                .thenReturn(List.of(p1));

        List<ConsultationNotePreset> result = service.listPresets("TREATMENT_NOTES");

        assertThat(result).containsExactly(p1);
    }

    @Test
    void createPreset_blankText_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);

        assertThatThrownBy(() -> service.createPreset("TREATMENT_NOTES", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void createPreset_validText_savesTrimmedWithHospitalIdAndNextDisplayOrder() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(presetRepository.findByHospitalIdAndFieldTypeAndIsActiveTrueOrderByDisplayOrderAsc(1L, "TREATMENT_NOTES"))
                .thenReturn(List.of(new ConsultationNotePreset(), new ConsultationNotePreset())); // 2 existing
        when(presetRepository.save(any(ConsultationNotePreset.class))).thenAnswer(inv -> inv.getArgument(0));

        ConsultationNotePreset result = service.createPreset("TREATMENT_NOTES", "  Avoid oily food  ");

        assertThat(result.getText()).isEqualTo("Avoid oily food");
        assertThat(result.getHospitalId()).isEqualTo(1L);
        assertThat(result.getFieldType()).isEqualTo("TREATMENT_NOTES");
        assertThat(result.getDisplayOrder()).isEqualTo(2);
        assertThat(result.getIsActive()).isTrue();
    }

    @Test
    void updatePreset_notFoundForHospital_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(presetRepository.findByIdAndHospitalId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePreset(99L, "New text", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void updatePreset_updatesTextAndDisplayOrder() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        ConsultationNotePreset existing = new ConsultationNotePreset();
        existing.setId(5L);
        existing.setHospitalId(1L);
        existing.setText("Old text");
        existing.setDisplayOrder(0);
        when(presetRepository.findByIdAndHospitalId(5L, 1L)).thenReturn(Optional.of(existing));
        when(presetRepository.save(any(ConsultationNotePreset.class))).thenAnswer(inv -> inv.getArgument(0));

        ConsultationNotePreset result = service.updatePreset(5L, "New text", 3);

        assertThat(result.getText()).isEqualTo("New text");
        assertThat(result.getDisplayOrder()).isEqualTo(3);
    }

    @Test
    void deletePreset_softDeletesWithinHospitalScope() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        ConsultationNotePreset existing = new ConsultationNotePreset();
        existing.setId(5L);
        existing.setHospitalId(1L);
        existing.setIsActive(true);
        when(presetRepository.findByIdAndHospitalId(5L, 1L)).thenReturn(Optional.of(existing));
        when(presetRepository.save(any(ConsultationNotePreset.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deletePreset(5L);

        assertThat(existing.getIsActive()).isFalse();
        verify(presetRepository).save(existing);
    }

    @Test
    void deletePreset_notFoundForHospital_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(presetRepository.findByIdAndHospitalId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deletePreset(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=ConsultationNotePresetServiceTest -q`
Expected: FAIL (compile error) — `ConsultationNotePresetService` doesn't exist yet.

- [ ] **Step 4: Create the service**

Create `backend/src/main/java/com/hms/service/hospital/ConsultationNotePresetService.java`:

```java
package com.hms.service.hospital;

import com.hms.entity.ConsultationNotePreset;
import com.hms.repository.ConsultationNotePresetRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConsultationNotePresetService {

    @Autowired
    private ConsultationNotePresetRepository presetRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    public List<ConsultationNotePreset> listPresets(String fieldType) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return presetRepository.findByHospitalIdAndFieldTypeAndIsActiveTrueOrderByDisplayOrderAsc(hospitalId, fieldType);
    }

    public ConsultationNotePreset createPreset(String fieldType, String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Preset text is required");
        }
        Long hospitalId = securityHelper.getCurrentHospitalId();
        int nextOrder = presetRepository
                .findByHospitalIdAndFieldTypeAndIsActiveTrueOrderByDisplayOrderAsc(hospitalId, fieldType)
                .size();

        ConsultationNotePreset preset = new ConsultationNotePreset();
        preset.setHospitalId(hospitalId);
        preset.setFieldType(fieldType);
        preset.setText(text.trim());
        preset.setDisplayOrder(nextOrder);
        preset.setIsActive(true);
        return presetRepository.save(preset);
    }

    public ConsultationNotePreset updatePreset(Long id, String text, Integer displayOrder) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        ConsultationNotePreset preset = presetRepository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new RuntimeException("Preset not found"));

        if (text != null && !text.trim().isEmpty()) {
            preset.setText(text.trim());
        }
        if (displayOrder != null) {
            preset.setDisplayOrder(displayOrder);
        }
        return presetRepository.save(preset);
    }

    public void deletePreset(Long id) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        ConsultationNotePreset preset = presetRepository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new RuntimeException("Preset not found"));
        preset.setIsActive(false);
        presetRepository.save(preset);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=ConsultationNotePresetServiceTest -q`
Expected: PASS (7 tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hms/dto/ConsultationNotePresetDTO.java backend/src/main/java/com/hms/service/hospital/ConsultationNotePresetService.java backend/src/test/java/com/hms/service/hospital/ConsultationNotePresetServiceTest.java
git commit -m "Add ConsultationNotePresetService with hospital-scoped CRUD"
```

---

## Task 3: `ConsultationNotePresetController` with tests

**Files:**
- Create: `backend/src/main/java/com/hms/controller/hospital/ConsultationNotePresetController.java`
- Test: `backend/src/test/java/com/hms/controller/hospital/ConsultationNotePresetControllerTest.java` (new)

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/hms/controller/hospital/ConsultationNotePresetControllerTest.java`:

```java
package com.hms.controller.hospital;

import com.hms.entity.ConsultationNotePreset;
import com.hms.security.JwtUtil;
import com.hms.service.AuditLogService;
import com.hms.service.hospital.ConsultationNotePresetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(ConsultationNotePresetController.class)
@Import(ConsultationNotePresetControllerTest.MethodSecurityTestConfig.class)
class ConsultationNotePresetControllerTest {

    // @WebMvcTest does not load SecurityConfig (a plain @Configuration bean), so
    // @PreAuthorize on the controller is never enforced without this.
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean private ConsultationNotePresetService presetService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private AuditLogService auditLogService;

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void listPresets_returnsOkForHospitalAdmin() throws Exception {
        when(presetService.listPresets("TREATMENT_NOTES")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/hospital/consultation-note-presets")
                        .param("fieldType", "TREATMENT_NOTES")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void listPresets_returnsOkForDoctor() throws Exception {
        when(presetService.listPresets("TREATMENT_NOTES")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/hospital/consultation-note-presets")
                        .param("fieldType", "TREATMENT_NOTES")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void listPresets_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(get("/hospital/consultation-note-presets")
                        .param("fieldType", "TREATMENT_NOTES")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void createPreset_returnsOkWhenServiceSucceeds() throws Exception {
        ConsultationNotePreset saved = new ConsultationNotePreset();
        saved.setId(1L);
        saved.setFieldType("TREATMENT_NOTES");
        saved.setText("Avoid oily food");
        saved.setDisplayOrder(0);
        when(presetService.createPreset(eq("TREATMENT_NOTES"), eq("Avoid oily food"))).thenReturn(saved);

        mockMvc.perform(post("/hospital/consultation-note-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldType\":\"TREATMENT_NOTES\",\"text\":\"Avoid oily food\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Avoid oily food"));
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void createPreset_returnsBadRequestWhenServiceThrows() throws Exception {
        when(presetService.createPreset(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Preset text is required"));

        mockMvc.perform(post("/hospital/consultation-note-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldType\":\"TREATMENT_NOTES\",\"text\":\"\"}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void createPreset_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(post("/hospital/consultation-note-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldType\":\"TREATMENT_NOTES\",\"text\":\"Avoid oily food\"}")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void updatePreset_returnsOkWhenFound() throws Exception {
        ConsultationNotePreset updated = new ConsultationNotePreset();
        updated.setId(5L);
        updated.setText("Updated text");
        when(presetService.updatePreset(eq(5L), eq("Updated text"), any())).thenReturn(updated);

        mockMvc.perform(put("/hospital/consultation-note-presets/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Updated text\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Updated text"));
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void updatePreset_returnsBadRequestWhenNotFound() throws Exception {
        when(presetService.updatePreset(eq(999L), any(), any()))
                .thenThrow(new RuntimeException("Preset not found"));

        mockMvc.perform(put("/hospital/consultation-note-presets/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"X\"}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void deletePreset_returnsOkWhenSuccessful() throws Exception {
        mockMvc.perform(delete("/hospital/consultation-note-presets/7").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void deletePreset_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(delete("/hospital/consultation-note-presets/7").with(csrf()))
                .andExpect(status().isForbidden());
    }
}
```

If, when you run this, Spring complains about missing beans beyond `JwtUtil`/`AuditLogService` (e.g. a global `@ControllerAdvice` or filter needing another bean), add the additional `@MockBean` fields needed — the error message will name the missing bean type directly. Do not change the test's assertions or intent, only add whatever `@MockBean` declarations are needed for the Spring context to start.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=ConsultationNotePresetControllerTest -q`
Expected: FAIL (compile error) — `ConsultationNotePresetController` doesn't exist yet.

- [ ] **Step 3: Create the controller**

Create `backend/src/main/java/com/hms/controller/hospital/ConsultationNotePresetController.java`:

```java
package com.hms.controller.hospital;

import com.hms.dto.ConsultationNotePresetDTO;
import com.hms.entity.ConsultationNotePreset;
import com.hms.service.hospital.ConsultationNotePresetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/hospital/consultation-note-presets")
public class ConsultationNotePresetController {

    @Autowired
    private ConsultationNotePresetService presetService;

    private ConsultationNotePresetDTO toDto(ConsultationNotePreset p) {
        return new ConsultationNotePresetDTO(p.getId(), p.getFieldType(), p.getText(), p.getDisplayOrder());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> listPresets(@RequestParam String fieldType) {
        List<ConsultationNotePresetDTO> dtos = presetService.listPresets(fieldType).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> createPreset(@RequestBody ConsultationNotePresetDTO dto) {
        try {
            ConsultationNotePreset saved = presetService.createPreset(dto.getFieldType(), dto.getText());
            return ResponseEntity.ok(toDto(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> updatePreset(@PathVariable Long id, @RequestBody ConsultationNotePresetDTO dto) {
        try {
            ConsultationNotePreset saved = presetService.updatePreset(id, dto.getText(), dto.getDisplayOrder());
            return ResponseEntity.ok(toDto(saved));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> deletePreset(@PathVariable Long id) {
        try {
            presetService.deletePreset(id);
            return ResponseEntity.ok("Preset deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=ConsultationNotePresetControllerTest -q`
Expected: PASS (10 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/controller/hospital/ConsultationNotePresetController.java backend/src/test/java/com/hms/controller/hospital/ConsultationNotePresetControllerTest.java
git commit -m "Add ConsultationNotePresetController with role-gated CRUD endpoints"
```

---

## Task 4: Database migration + canonical schema update

**Files:**
- Modify: `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`
- Modify: `setup/schema-full.sql`

- [ ] **Step 1: Add the migration method**

In `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`, add a new call at the end of `runMigrations()`'s call list (currently ending with `migratePatientAgeToDateOfBirth();`):

```java
        migratePatientAgeToDateOfBirth();
        ensureConsultationNotePresetsTable(); // NEW
```

Add the method itself, following the exact style of `ensureWhatsAppConfigTable()` (same file):

```java
    /**
     * Creates the consultation_note_presets table if it does not exist.
     * Stores per-hospital quick-note phrases doctors can insert with one
     * click into Treatment Notes (and, in future, other consultation
     * fields — see field_type).
     * ddl-auto=update cannot create tables from scratch — this runner
     * bridges that gap.
     */
    private void ensureConsultationNotePresetsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'consultation_note_presets'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE consultation_note_presets (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  field_type VARCHAR(30) NOT NULL," +
                    "  text VARCHAR(255) NOT NULL," +
                    "  display_order INT NOT NULL DEFAULT 0," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: consultation_note_presets table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (consultation_note_presets): {}", e.getMessage());
        }
    }
```

- [ ] **Step 2: Update the canonical schema**

In `setup/schema-full.sql`, add a new table definition (append near the end of the file, or alongside other small hospital-scoped tables like `hospital_fees` if one exists — check the file for the nearest similar table and place it adjacent for readability):

```sql
CREATE TABLE `consultation_note_presets` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `hospital_id` bigint NOT NULL,
  `field_type` varchar(30) NOT NULL,
  `text` varchar(255) NOT NULL,
  `display_order` int NOT NULL DEFAULT '0',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK_consultation_note_presets_hospital` (`hospital_id`),
  CONSTRAINT `FK_consultation_note_presets_hospital` FOREIGN KEY (`hospital_id`) REFERENCES `hospitals` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- [ ] **Step 3: Compile check**

Run: `cd backend && mvn -q -o clean compile`
Expected: no output = success.

- [ ] **Step 4: Run full backend test suite**

Run: `cd backend && mvn test -q`
Expected: BUILD SUCCESS, all tests pass including the new `ConsultationNotePresetServiceTest` and `ConsultationNotePresetControllerTest`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java setup/schema-full.sql
git commit -m "Add DB migration for consultation_note_presets table"
```

---

## Task 5: Backend live verification against a real database

No automated test for this task — verifies the migration and full request/response cycle against the actual dev database.

**Files:** none (verification only)

- [ ] **Step 1: Restart the backend**

Stop whatever backend process is currently running (`netstat -ano | grep :8080` on Windows via Git Bash, then stop that PID), then:

```bash
cd backend && (mvn -q spring-boot:run > /tmp/preset-verify.log 2>&1 &)
```

Wait for `Started HospitalManagementSystemApplication` in the log, then check for the migration log line:

```bash
grep "consultation_note_presets" /tmp/preset-verify.log
```

Expected: `DB migration applied: consultation_note_presets table created` (or `DB migration skipped...` if it already exists from a prior run — check the reason if so).

- [ ] **Step 2: Verify the schema directly**

```bash
mysql -u root -p -D <db_name> -e "DESCRIBE consultation_note_presets;"
```

Expected: columns `id`, `hospital_id`, `field_type`, `text`, `display_order`, `is_active`, `created_at`.

- [ ] **Step 3: Verify the API end-to-end**

Craft a JWT for a test hospital admin or doctor (same approach used elsewhere in this project — HS256, signed with the raw UTF-8 bytes of `JWT_SECRET` from `backend/.env`). Create a preset:

```bash
curl -s -X POST "http://localhost:8080/hospital/consultation-note-presets" -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"fieldType":"TREATMENT_NOTES","text":"Avoid oily food"}'
```

Expected: `200 OK`, response includes `"text":"Avoid oily food"`, `"displayOrder":0`.

List it back:

```bash
curl -s "http://localhost:8080/hospital/consultation-note-presets?fieldType=TREATMENT_NOTES" -H "Authorization: Bearer <token>"
```

Expected: `200 OK`, array containing the preset just created.

Update it:

```bash
curl -s -X PUT "http://localhost:8080/hospital/consultation-note-presets/<id>" -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"text":"Avoid oily and spicy food"}'
```

Expected: `200 OK`, `"text":"Avoid oily and spicy food"`.

Delete it:

```bash
curl -s -X DELETE "http://localhost:8080/hospital/consultation-note-presets/<id>" -H "Authorization: Bearer <token>"
```

Expected: `200 OK`. Re-run the list call — the deleted preset should no longer appear (soft-deleted, `is_active = 0`).

---

## Task 6: Frontend — `hospitalService.js` API functions

**Files:**
- Modify: `frontend/src/services/hospitalService.js`

- [ ] **Step 1: Add the four functions**

In `frontend/src/services/hospitalService.js`, add these functions near the existing `getCustomFees`/`addCustomFee`/`updateCustomFee`/`deleteCustomFee` group (same file, same object) — read that section first to match the exact surrounding style, then add:

```javascript
    getConsultationNotePresets: async (fieldType) => {
        const response = await apiClient.get(`/hospital/consultation-note-presets?fieldType=${fieldType}`);
        return response.data;
    },

    createConsultationNotePreset: async (data) => {
        const response = await apiClient.post('/hospital/consultation-note-presets', data);
        return response.data;
    },

    updateConsultationNotePreset: async (id, data) => {
        const response = await apiClient.put(`/hospital/consultation-note-presets/${id}`, data);
        return response.data;
    },

    deleteConsultationNotePreset: async (id) => {
        const response = await apiClient.delete(`/hospital/consultation-note-presets/${id}`);
        return response.data;
    },
```

- [ ] **Step 2: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/services/hospitalService.js
git commit -m "Add consultation note preset API functions"
```

---

## Task 7: Frontend — `NotePresetsManager.jsx` (core list/add/edit/delete/reorder UI)

**Files:**
- Create: `frontend/src/components/NotePresetsManager.jsx`

This is a self-contained, presentational component with no modal chrome — it fetches its own data on mount and renders a table, matching the visual style of the existing "Custom Charges" table in `HospitalAdminDashboard.jsx` (`frontend/src/pages/hospital/HospitalAdminDashboard.jsx:2030-2087`). It's used two ways: wrapped in a modal (Task 8) and rendered full-page under a new Admin tab (Task 9).

- [ ] **Step 1: Create the component**

Create `frontend/src/components/NotePresetsManager.jsx`:

```jsx
import React, { useState, useEffect, useCallback } from 'react';
import hospitalService from '../services/hospitalService';
import { useToast } from '../context/ToastContext';
import ConfirmationModal from './ConfirmationModal';

/**
 * Lists, adds, edits, deletes, and reorders quick-note presets for a given
 * fieldType (currently only 'TREATMENT_NOTES' is used). Self-contained: does
 * its own data fetching, so it can be dropped into a modal or a full page.
 */
const NotePresetsManager = ({ fieldType }) => {
    const { success, error: toastError } = useToast();
    const [presets, setPresets] = useState([]);
    const [loading, setLoading] = useState(true);
    const [newText, setNewText] = useState('');
    const [adding, setAdding] = useState(false);
    const [editingId, setEditingId] = useState(null);
    const [editingText, setEditingText] = useState('');
    const [deleteConfirm, setDeleteConfirm] = useState({ isOpen: false, id: null });

    const loadPresets = useCallback(async () => {
        setLoading(true);
        try {
            const data = await hospitalService.getConsultationNotePresets(fieldType);
            setPresets(data || []);
        } catch (err) {
            toastError('Failed to load quick notes');
        } finally {
            setLoading(false);
        }
    }, [fieldType, toastError]);

    useEffect(() => {
        loadPresets();
    }, [loadPresets]);

    const handleAdd = async (e) => {
        e.preventDefault();
        if (!newText.trim()) return;
        setAdding(true);
        try {
            const created = await hospitalService.createConsultationNotePreset({ fieldType, text: newText.trim() });
            setPresets(prev => [...prev, created]);
            setNewText('');
            success('Quick note added');
        } catch (err) {
            toastError(err?.response?.data || 'Failed to add quick note');
        } finally {
            setAdding(false);
        }
    };

    const handleStartEdit = (preset) => {
        setEditingId(preset.id);
        setEditingText(preset.text);
    };

    const handleSaveEdit = async (id) => {
        if (!editingText.trim()) return;
        try {
            const updated = await hospitalService.updateConsultationNotePreset(id, { text: editingText.trim() });
            setPresets(prev => prev.map(p => (p.id === id ? updated : p)));
            setEditingId(null);
            success('Quick note updated');
        } catch (err) {
            toastError('Failed to update quick note');
        }
    };

    const handleDelete = (id) => {
        setDeleteConfirm({ isOpen: true, id });
    };

    const confirmDelete = async () => {
        const id = deleteConfirm.id;
        try {
            await hospitalService.deleteConsultationNotePreset(id);
            setPresets(prev => prev.filter(p => p.id !== id));
            success('Quick note deleted');
        } catch (err) {
            toastError('Failed to delete quick note');
        }
    };

    const handleMove = async (index, direction) => {
        const targetIndex = index + direction;
        if (targetIndex < 0 || targetIndex >= presets.length) return;

        const a = presets[index];
        const b = presets[targetIndex];
        try {
            const [updatedA, updatedB] = await Promise.all([
                hospitalService.updateConsultationNotePreset(a.id, { displayOrder: b.displayOrder }),
                hospitalService.updateConsultationNotePreset(b.id, { displayOrder: a.displayOrder }),
            ]);
            const next = [...presets];
            next[index] = updatedB;
            next[targetIndex] = updatedA;
            setPresets(next);
        } catch (err) {
            toastError('Failed to reorder quick notes');
        }
    };

    return (
        <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm space-y-6">
            <div>
                <h3 className="text-lg font-semibold text-gray-900 mb-1">Quick Notes</h3>
                <p className="text-xs text-gray-500">
                    Common phrases that appear as one-click buttons under Treatment Notes during a consultation.
                </p>
            </div>

            <form onSubmit={handleAdd} className="flex gap-2">
                <input
                    type="text"
                    value={newText}
                    onChange={(e) => setNewText(e.target.value)}
                    placeholder="e.g. Avoid oily food"
                    maxLength={255}
                    className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent outline-none"
                />
                <button
                    type="submit"
                    disabled={adding || !newText.trim()}
                    className="bg-gray-950 text-white text-xs font-semibold px-4 py-2 rounded-lg hover:bg-gray-800 transition disabled:opacity-50"
                >
                    + Add
                </button>
            </form>

            {loading ? (
                <p className="text-sm text-gray-500">Loading...</p>
            ) : presets.length === 0 ? (
                <div className="text-center py-8 border border-dashed border-gray-200 rounded-xl bg-gray-50">
                    <p className="text-sm text-gray-500">No quick notes added yet. Add your first one above.</p>
                </div>
            ) : (
                <div className="divide-y divide-gray-200">
                    {presets.map((preset, index) => (
                        <div key={preset.id} className="flex items-center gap-3 py-3">
                            <div className="flex flex-col">
                                <button
                                    type="button"
                                    onClick={() => handleMove(index, -1)}
                                    disabled={index === 0}
                                    className="text-gray-400 hover:text-gray-700 disabled:opacity-30 text-xs leading-none"
                                    aria-label="Move up"
                                >
                                    ▲
                                </button>
                                <button
                                    type="button"
                                    onClick={() => handleMove(index, 1)}
                                    disabled={index === presets.length - 1}
                                    className="text-gray-400 hover:text-gray-700 disabled:opacity-30 text-xs leading-none"
                                    aria-label="Move down"
                                >
                                    ▼
                                </button>
                            </div>

                            {editingId === preset.id ? (
                                <input
                                    type="text"
                                    value={editingText}
                                    onChange={(e) => setEditingText(e.target.value)}
                                    maxLength={255}
                                    autoFocus
                                    className="flex-1 border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent outline-none"
                                />
                            ) : (
                                <span className="flex-1 text-sm text-gray-800">{preset.text}</span>
                            )}

                            <div className="flex gap-2 text-sm">
                                {editingId === preset.id ? (
                                    <>
                                        <button onClick={() => handleSaveEdit(preset.id)} className="text-emerald-600 hover:text-emerald-800 font-medium">Save</button>
                                        <button onClick={() => setEditingId(null)} className="text-gray-500 hover:text-gray-700 font-medium">Cancel</button>
                                    </>
                                ) : (
                                    <>
                                        <button onClick={() => handleStartEdit(preset)} className="text-indigo-600 hover:text-indigo-900 font-medium">Edit</button>
                                        <button onClick={() => handleDelete(preset.id)} className="text-red-600 hover:text-red-900 font-medium">Delete</button>
                                    </>
                                )}
                            </div>
                        </div>
                    ))}
                </div>
            )}

            <ConfirmationModal
                isOpen={deleteConfirm.isOpen}
                title="Delete Quick Note"
                message="Are you sure you want to delete this quick note? It will no longer appear as a one-click option."
                onConfirm={confirmDelete}
                onCancel={() => setDeleteConfirm({ isOpen: false, id: null })}
            />
        </div>
    );
};

export default NotePresetsManager;
```

- [ ] **Step 2: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 3: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/NotePresetsManager.jsx
git commit -m "Add NotePresetsManager component for quick-note CRUD"
```

---

## Task 8: Frontend — `ManageNotePresetsModal.jsx` (modal wrapper)

**Files:**
- Create: `frontend/src/components/ManageNotePresetsModal.jsx`

- [ ] **Step 1: Create the component**

Create `frontend/src/components/ManageNotePresetsModal.jsx`:

```jsx
import React from 'react';
import NotePresetsManager from './NotePresetsManager';

/**
 * Modal wrapper around NotePresetsManager, used from ConsultationModal so a
 * doctor can manage quick notes without leaving the consultation screen.
 */
const ManageNotePresetsModal = ({ isOpen, onClose, fieldType }) => {
    if (!isOpen) return null;

    return (
        <div
            className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[70] p-4"
            onClick={onClose}
            role="presentation"
        >
            <div
                role="dialog"
                aria-modal="true"
                className="bg-gray-50 rounded-2xl shadow-2xl w-full max-w-lg max-h-[85vh] overflow-y-auto"
                onClick={(e) => e.stopPropagation()}
            >
                <div className="flex justify-between items-center p-4 border-b border-gray-200 bg-white rounded-t-2xl sticky top-0">
                    <h2 className="text-base font-bold text-gray-900">Manage Quick Notes</h2>
                    <button onClick={onClose} className="text-gray-400 hover:text-gray-700 text-xl leading-none" aria-label="Close">
                        &times;
                    </button>
                </div>
                <div className="p-4">
                    <NotePresetsManager fieldType={fieldType} />
                </div>
            </div>
        </div>
    );
};

export default ManageNotePresetsModal;
```

- [ ] **Step 2: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 3: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/ManageNotePresetsModal.jsx
git commit -m "Add ManageNotePresetsModal wrapper for consultation-screen use"
```

---

## Task 9: Frontend — wire chips + Manage link into `ConsultationModal.jsx`

**Files:**
- Modify: `frontend/src/components/ConsultationModal.jsx`

- [ ] **Step 1: Add imports and state**

In `frontend/src/components/ConsultationModal.jsx`, add to the imports at the top (after the existing `IpdAdmitModal` import on line 7):

```javascript
import ManageNotePresetsModal from './ManageNotePresetsModal';
```

Add new state near the other `useState` declarations (after `const [admitModalOpd, setAdmitModalOpd] = useState(null);` on line 16):

```javascript
    const [notePresets, setNotePresets] = useState([]);
    const [showManagePresets, setShowManagePresets] = useState(false);
```

- [ ] **Step 2: Fetch presets when the modal opens**

Add a new `useEffect`, placed after the existing fees-fetching `useEffect` (the one starting `useEffect(() => { if (isOpen) { const fetchFees = async () => {...` at line 33, which closes with `}, [isOpen, opd, hasBilling]);` at line 66 — add this new effect immediately after that closing line):

```javascript
    useEffect(() => {
        if (isOpen) {
            hospitalService.getConsultationNotePresets('TREATMENT_NOTES')
                .then(data => setNotePresets(data || []))
                .catch(() => setNotePresets([]));
        }
    }, [isOpen]);
```

- [ ] **Step 3: Add the insert handler**

Add this function next to the existing `handleChange` function (`frontend/src/components/ConsultationModal.jsx:211-213`):

```javascript
    const handleInsertPreset = (text) => {
        setFormData(prev => ({
            ...prev,
            treatmentNotes: prev.treatmentNotes ? `${prev.treatmentNotes}\n${text}` : text,
        }));
    };
```

- [ ] **Step 4: Render the chip row under Treatment Notes**

Find the `Treatment Notes` `CharCountInput` block (`frontend/src/components/ConsultationModal.jsx:840-848`):

```jsx
                                    <CharCountInput
                                        label="Treatment Notes"
                                        textarea
                                        rows={4}
                                        value={formData.treatmentNotes}
                                        onChange={(e) => handleChange('treatmentNotes', e.target.value)}
                                        maxLength={500}
                                        placeholder="Enter treatment plan and notes..."
                                    />
```

Replace it with (same block, plus a new chip row immediately after):

```jsx
                                    <CharCountInput
                                        label="Treatment Notes"
                                        textarea
                                        rows={4}
                                        value={formData.treatmentNotes}
                                        onChange={(e) => handleChange('treatmentNotes', e.target.value)}
                                        maxLength={500}
                                        placeholder="Enter treatment plan and notes..."
                                    />

                                    <div className="flex flex-wrap items-center gap-2 -mt-2">
                                        {notePresets.map(preset => (
                                            <button
                                                key={preset.id}
                                                type="button"
                                                onClick={() => handleInsertPreset(preset.text)}
                                                className="inline-flex items-center px-3 py-1 text-xs font-medium bg-teal-50 text-teal-700 border border-teal-200 rounded-full hover:bg-teal-100 transition"
                                            >
                                                {preset.text}
                                            </button>
                                        ))}
                                        {notePresets.length === 0 && (
                                            <button
                                                type="button"
                                                onClick={() => setShowManagePresets(true)}
                                                className="text-xs text-gray-500 hover:text-gray-700 underline"
                                            >
                                                Add your first quick note
                                            </button>
                                        )}
                                        {notePresets.length > 0 && (
                                            <button
                                                type="button"
                                                onClick={() => setShowManagePresets(true)}
                                                className="text-xs text-gray-500 hover:text-gray-700 underline ml-1"
                                            >
                                                Manage
                                            </button>
                                        )}
                                    </div>
```

- [ ] **Step 5: Render the manage modal**

Find the `<IpdAdmitModal ... />` block at `frontend/src/components/ConsultationModal.jsx:1035-1045`:

```jsx
                <IpdAdmitModal
                    isOpen={showIpdAdmitModal}
                    opd={admitModalOpd || opd}
                    initialDiagnosis={formData.diagnosis || formData.symptoms || (admitModalOpd || opd)?.problem || ''}
                    onClose={() => setShowIpdAdmitModal(false)}
                    onSuccess={() => {
                        setShowIpdAdmitModal(false);
                        onSuccess("Patient admitted to IPD successfully!");
                        onClose();
                    }}
                />
```

Add the new modal as a sibling immediately after its closing `/>`:

```jsx
                <ManageNotePresetsModal
                    isOpen={showManagePresets}
                    onClose={() => {
                        setShowManagePresets(false);
                        hospitalService.getConsultationNotePresets('TREATMENT_NOTES')
                            .then(data => setNotePresets(data || []))
                            .catch(() => {});
                    }}
                    fieldType="TREATMENT_NOTES"
                />
```

(Refetching on close keeps the chip row in sync with anything added/edited/deleted/reordered while the manage modal was open.)

- [ ] **Step 6: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 7: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/ConsultationModal.jsx
git commit -m "Add quick-note chips and manage link to Treatment Notes"
```

---

## Task 10: Frontend — "Quick Notes" tab in Hospital Admin's Administration group

**Files:**
- Modify: `frontend/src/pages/hospital/HospitalAdminDashboard.jsx`

- [ ] **Step 1: Add the import**

Add near the other component imports (alongside `import ConfirmationModal from '../../components/ConfirmationModal';` at line 9):

```javascript
import NotePresetsManager from '../../components/NotePresetsManager';
```

- [ ] **Step 2: Add the tab entry**

Find the `tabs` array entries for `settings`/`support` (`frontend/src/pages/hospital/HospitalAdminDashboard.jsx:1312-1313`):

```javascript
        { id: 'settings', label: 'Settings', icon: null, requiredModule: null },
        { id: 'support', label: 'Support', icon: null, requiredModule: null },
```

Add a new entry immediately after:

```javascript
        { id: 'settings', label: 'Settings', icon: null, requiredModule: null },
        { id: 'support', label: 'Support', icon: null, requiredModule: null },
        { id: 'quick-notes', label: 'Quick Notes', icon: null, requiredModule: null },
```

- [ ] **Step 3: Add it to the Administration sidebar group**

Find `SIDEBAR_GROUPS` (`frontend/src/pages/hospital/HospitalAdminDashboard.jsx:1336`):

```javascript
        { id: 'group-administration', label: 'Administration', tabIds: ['settings', 'support'] },
```

Replace with:

```javascript
        { id: 'group-administration', label: 'Administration', tabIds: ['settings', 'support', 'quick-notes'] },
```

- [ ] **Step 4: Render the tab's content**

Find the `activeTab === 'hospital-inventory'` block (`frontend/src/pages/hospital/HospitalAdminDashboard.jsx:2117-2119`):

```jsx
                        {activeTab === 'hospital-inventory' && (
                            <HospitalInventoryTab />
                        )}
```

Add a new block immediately after it:

```jsx
                        {activeTab === 'hospital-inventory' && (
                            <HospitalInventoryTab />
                        )}

                        {activeTab === 'quick-notes' && (
                            <div className="max-w-2xl mx-auto my-4">
                                <NotePresetsManager fieldType="TREATMENT_NOTES" />
                            </div>
                        )}
```

- [ ] **Step 5: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 6: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/hospital/HospitalAdminDashboard.jsx
git commit -m "Add Quick Notes tab under Hospital Admin Administration group"
```

---

## Task 11: Full-stack live verification

This project's established verification method: restart both servers cleanly, then drive the real UI with Playwright (headless Chromium) and inspect screenshots, since there's no frontend test runner configured.

**Files:** none (verification only)

- [ ] **Step 1: Restart backend and frontend cleanly**

Stop and restart both dev servers (same pattern as prior verification tasks in this project): `mvn spring-boot:run` for backend, `npm run dev` for frontend, waiting for their respective ready log lines.

- [ ] **Step 2: Verify the "Quick Notes" admin tab**

Using a Playwright script (craft a JWT for a `HOSPITAL_ADMIN` test user, inject into `sessionStorage`, navigate to `/hospital/admin?tab=quick-notes`):
- Screenshot the empty state ("No quick notes added yet...")
- Type a phrase (e.g. "Avoid oily food") into the add input and submit
- Screenshot again — confirm the new preset appears in the list
- Click "Edit", change the text, click "Save" — confirm it updates
- Click the up/down arrows on two presets (after adding a second one) — confirm their order swaps
- Click "Delete", confirm via the confirmation dialog — confirm it disappears from the list

- [ ] **Step 3: Verify the chips in Consultation Notes**

Navigate to a doctor's Consultation view for a patient with an active OPD/appointment, open the Clinical Notes tab:
- Screenshot — confirm chip buttons appear under Treatment Notes matching whatever presets exist for the hospital
- Click a chip — confirm its text is appended into the Treatment Notes textarea
- Click a second, different chip — confirm the second phrase is appended on a new line, not replacing the first
- Click "Manage" next to the chips — confirm the same modal/list opens inline
- Add a new preset from within this modal, close it — confirm the new chip now appears in the chip row (refetch-on-close working)

- [ ] **Step 4: Verify role gating**

Confirm a `RECEPTIONIST`-role API call to `GET /hospital/consultation-note-presets` returns `403 Forbidden` (receptionists never see this UI, but the backend gate should hold regardless) — a simple `curl` check with a receptionist-role JWT is sufficient, no UI needed for this check.

- [ ] **Step 5: Final full build and test check**

```bash
cd backend && mvn -q -o clean compile && mvn test -q
cd frontend && npx tsc --noEmit && npx vite build --mode development
```

Expected: both succeed with no errors, full backend test suite passes (confirms nothing else in the suite broke from these changes).

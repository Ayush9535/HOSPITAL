# Prescription Presets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a doctor save a named bundle of medicines (a "preset") once, then apply the whole bundle to a patient's prescription via a dropdown instead of re-typing each medicine — and let any prescription row (preset-inserted or manual) be edited in place afterward.

**Architecture:** Two new hospital-scoped tables: `prescription_presets` (name + metadata) and `prescription_preset_items` (the medicine rows within a preset, FK to the preset). A Controller+Service+Repository trio under `/hospital/prescription-presets` (mirroring the already-built `ConsultationNotePreset` CRUD, but each preset now carries a list of items rather than a single string) exposes list/create/update/delete, gated to `HOSPITAL_ADMIN`/`DOCTOR`. On the frontend: a `<select>` dropdown in the Prescription tab of `ConsultationModal.jsx` lists preset names — picking one appends all its medicines to the prescription list. A new "Edit" action is added to each prescription row (previously remove-only) so a doctor can adjust dosage/frequency/etc. after applying a preset. A reusable `PrescriptionPresetsManager.jsx` component (list/create/edit/delete/reorder, each preset expandable to its medicine rows) is shown two ways: a modal opened via "Manage Presets" next to the dropdown, and a full page under a new tab in Hospital Admin's "Administration" sidebar group — following the exact same dual-entry-point pattern already used for Quick Notes.

**Tech Stack:** Spring Boot / Java 17 / Hibernate (JPA) / MySQL 8, JUnit 5 + Mockito + AssertJ + `@WebMvcTest`/MockMvc for backend tests. React / Vite frontend, no test runner configured (manual build + live verification).

---

## Task 1: `PrescriptionPreset` + `PrescriptionPresetItem` entities and repositories

**Files:**
- Create: `backend/src/main/java/com/hms/entity/PrescriptionPreset.java`
- Create: `backend/src/main/java/com/hms/entity/PrescriptionPresetItem.java`
- Create: `backend/src/main/java/com/hms/repository/PrescriptionPresetRepository.java`
- Create: `backend/src/main/java/com/hms/repository/PrescriptionPresetItemRepository.java`

- [ ] **Step 1: Create the `PrescriptionPreset` entity**

Create `backend/src/main/java/com/hms/entity/PrescriptionPreset.java`:

```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * A per-hospital, named bundle of medicines a doctor can apply to a
 * patient's prescription in one action instead of re-typing each medicine.
 * The medicine rows themselves live in PrescriptionPresetItem.
 */
@Entity
@Table(name = "prescription_presets")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrescriptionPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(nullable = false, length = 150)
    private String name;

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

- [ ] **Step 2: Create the `PrescriptionPresetItem` entity**

Create `backend/src/main/java/com/hms/entity/PrescriptionPresetItem.java`:

```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One medicine row within a PrescriptionPreset. Field names/lengths
 * deliberately mirror Prescription.java so an item maps 1:1 onto a
 * prescription row with no field-name translation needed.
 */
@Entity
@Table(name = "prescription_preset_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrescriptionPresetItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "preset_id", nullable = false)
    private Long presetId;

    @Column(name = "medicine_name", nullable = false)
    private String medicineName;

    @Column(length = 50)
    private String dosage;

    @Column(length = 50)
    private String frequency;

    @Column(length = 50)
    private String duration;

    @Column(length = 200)
    private String instructions;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
```

- [ ] **Step 3: Create the repositories**

Create `backend/src/main/java/com/hms/repository/PrescriptionPresetRepository.java`:

```java
package com.hms.repository;

import com.hms.entity.PrescriptionPreset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrescriptionPresetRepository extends JpaRepository<PrescriptionPreset, Long> {
    List<PrescriptionPreset> findByHospitalIdAndIsActiveTrueOrderByDisplayOrderAsc(Long hospitalId);
    Optional<PrescriptionPreset> findByIdAndHospitalId(Long id, Long hospitalId);
}
```

Create `backend/src/main/java/com/hms/repository/PrescriptionPresetItemRepository.java`:

```java
package com.hms.repository;

import com.hms.entity.PrescriptionPresetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrescriptionPresetItemRepository extends JpaRepository<PrescriptionPresetItem, Long> {
    List<PrescriptionPresetItem> findByPresetIdOrderBySortOrderAsc(Long presetId);
    void deleteByPresetId(Long presetId);
}
```

- [ ] **Step 4: Compile check**

Run: `cd backend && mvn -q -o compile`
Expected: no output = success.

- [ ] **Step 5: Commit**

```bash
cd e:/Projects/HOSPITAL
git add backend/src/main/java/com/hms/entity/PrescriptionPreset.java backend/src/main/java/com/hms/entity/PrescriptionPresetItem.java backend/src/main/java/com/hms/repository/PrescriptionPresetRepository.java backend/src/main/java/com/hms/repository/PrescriptionPresetItemRepository.java
git commit -m "Add PrescriptionPreset and PrescriptionPresetItem entities and repositories"
```

Stage ONLY these four files — do not run `git add -A` or `git add .`. There may be unrelated uncommitted changes in the working tree from other work; leave them untouched.

---

## Task 2: `PrescriptionPresetService` with tests

**Files:**
- Create: `backend/src/main/java/com/hms/dto/PrescriptionPresetItemDTO.java`
- Create: `backend/src/main/java/com/hms/dto/PrescriptionPresetDTO.java`
- Create: `backend/src/main/java/com/hms/service/hospital/PrescriptionPresetService.java`
- Test: `backend/src/test/java/com/hms/service/hospital/PrescriptionPresetServiceTest.java` (new)

- [ ] **Step 1: Create the DTOs**

Create `backend/src/main/java/com/hms/dto/PrescriptionPresetItemDTO.java`:

```java
package com.hms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrescriptionPresetItemDTO {
    private Long id;
    private String medicineName;
    private String dosage;
    private String frequency;
    private String duration;
    private String instructions;
}
```

Create `backend/src/main/java/com/hms/dto/PrescriptionPresetDTO.java`:

```java
package com.hms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrescriptionPresetDTO {
    private Long id;
    private String name;
    private List<PrescriptionPresetItemDTO> items;
    private Integer displayOrder;
}
```

- [ ] **Step 2: Write the failing tests**

Create `backend/src/test/java/com/hms/service/hospital/PrescriptionPresetServiceTest.java`:

```java
package com.hms.service.hospital;

import com.hms.entity.PrescriptionPreset;
import com.hms.entity.PrescriptionPresetItem;
import com.hms.repository.PrescriptionPresetItemRepository;
import com.hms.repository.PrescriptionPresetRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PrescriptionPresetServiceTest {

    @Mock PrescriptionPresetRepository presetRepository;
    @Mock PrescriptionPresetItemRepository itemRepository;
    @Mock SecurityContextHelper securityHelper;

    @InjectMocks PrescriptionPresetService service;

    private PrescriptionPresetItem item(String name) {
        PrescriptionPresetItem i = new PrescriptionPresetItem();
        i.setMedicineName(name);
        i.setDosage("500mg");
        i.setFrequency("1-0-1");
        i.setDuration("5 Days");
        i.setInstructions("After food");
        return i;
    }

    @Test
    void listPresets_returnsHospitalScopedActivePresetsInOrder() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        PrescriptionPreset p1 = new PrescriptionPreset();
        p1.setName("Fever Protocol");
        when(presetRepository.findByHospitalIdAndIsActiveTrueOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(p1));

        List<PrescriptionPreset> result = service.listPresets();

        assertThat(result).containsExactly(p1);
    }

    @Test
    void getItems_returnsItemsOrderedBySortOrder() {
        PrescriptionPresetItem i1 = item("Paracetamol");
        when(itemRepository.findByPresetIdOrderBySortOrderAsc(5L)).thenReturn(List.of(i1));

        List<PrescriptionPresetItem> result = service.getItems(5L);

        assertThat(result).containsExactly(i1);
    }

    @Test
    void createPreset_blankName_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);

        assertThatThrownBy(() -> service.createPreset("   ", List.of(item("Paracetamol"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void createPreset_noItems_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);

        assertThatThrownBy(() -> service.createPreset("Fever Protocol", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one medicine");
    }

    @Test
    void createPreset_valid_savesPresetAndItemsWithHospitalIdAndNextDisplayOrder() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(presetRepository.findByHospitalIdAndIsActiveTrueOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(new PrescriptionPreset())); // 1 existing
        when(presetRepository.save(any(PrescriptionPreset.class))).thenAnswer(inv -> {
            PrescriptionPreset p = inv.getArgument(0);
            p.setId(9L);
            return p;
        });
        when(itemRepository.save(any(PrescriptionPresetItem.class))).thenAnswer(inv -> inv.getArgument(0));

        PrescriptionPreset result = service.createPreset("  Fever Protocol  ", List.of(item("Paracetamol"), item("Cetirizine")));

        assertThat(result.getName()).isEqualTo("Fever Protocol");
        assertThat(result.getHospitalId()).isEqualTo(1L);
        assertThat(result.getDisplayOrder()).isEqualTo(1);
        assertThat(result.getIsActive()).isTrue();
        verify(itemRepository, org.mockito.Mockito.times(2)).save(any(PrescriptionPresetItem.class));
    }

    @Test
    void updatePreset_notFoundForHospital_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(presetRepository.findByIdAndHospitalId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePreset(99L, "New Name", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void updatePreset_withItems_deletesOldItemsAndSavesNewOnes() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        PrescriptionPreset existing = new PrescriptionPreset();
        existing.setId(5L);
        existing.setHospitalId(1L);
        existing.setName("Old Name");
        when(presetRepository.findByIdAndHospitalId(5L, 1L)).thenReturn(Optional.of(existing));
        when(presetRepository.save(any(PrescriptionPreset.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.save(any(PrescriptionPresetItem.class))).thenAnswer(inv -> inv.getArgument(0));

        PrescriptionPreset result = service.updatePreset(5L, "New Name", List.of(item("Ibuprofen")), 2);

        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getDisplayOrder()).isEqualTo(2);
        verify(itemRepository).deleteByPresetId(5L);
        verify(itemRepository).save(any(PrescriptionPresetItem.class));
    }

    @Test
    void updatePreset_withEmptyItemsList_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        PrescriptionPreset existing = new PrescriptionPreset();
        existing.setId(5L);
        existing.setHospitalId(1L);
        when(presetRepository.findByIdAndHospitalId(5L, 1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updatePreset(5L, "Name", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one medicine");
    }

    @Test
    void deletePreset_softDeletesWithinHospitalScope() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        PrescriptionPreset existing = new PrescriptionPreset();
        existing.setId(5L);
        existing.setHospitalId(1L);
        existing.setIsActive(true);
        when(presetRepository.findByIdAndHospitalId(5L, 1L)).thenReturn(Optional.of(existing));
        when(presetRepository.save(any(PrescriptionPreset.class))).thenAnswer(inv -> inv.getArgument(0));

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

Run: `cd backend && mvn test -Dtest=PrescriptionPresetServiceTest -q`
Expected: FAIL (compile error) — `PrescriptionPresetService` doesn't exist yet.

- [ ] **Step 4: Create the service**

Create `backend/src/main/java/com/hms/service/hospital/PrescriptionPresetService.java`:

```java
package com.hms.service.hospital;

import com.hms.entity.PrescriptionPreset;
import com.hms.entity.PrescriptionPresetItem;
import com.hms.repository.PrescriptionPresetItemRepository;
import com.hms.repository.PrescriptionPresetRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PrescriptionPresetService {

    @Autowired
    private PrescriptionPresetRepository presetRepository;

    @Autowired
    private PrescriptionPresetItemRepository itemRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    public List<PrescriptionPreset> listPresets() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return presetRepository.findByHospitalIdAndIsActiveTrueOrderByDisplayOrderAsc(hospitalId);
    }

    public List<PrescriptionPresetItem> getItems(Long presetId) {
        return itemRepository.findByPresetIdOrderBySortOrderAsc(presetId);
    }

    public PrescriptionPreset createPreset(String name, List<PrescriptionPresetItem> items) {
        // hospitalId is fetched first so every call path touches securityHelper
        // consistently, matching the pattern established in ConsultationNotePresetService.
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Preset name is required");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Preset must contain at least one medicine");
        }
        int nextOrder = presetRepository.findByHospitalIdAndIsActiveTrueOrderByDisplayOrderAsc(hospitalId).size();

        PrescriptionPreset preset = new PrescriptionPreset();
        preset.setHospitalId(hospitalId);
        preset.setName(name.trim());
        preset.setDisplayOrder(nextOrder);
        preset.setIsActive(true);
        PrescriptionPreset saved = presetRepository.save(preset);

        saveItems(saved.getId(), items);
        return saved;
    }

    public PrescriptionPreset updatePreset(Long id, String name, List<PrescriptionPresetItem> items, Integer displayOrder) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        PrescriptionPreset preset = presetRepository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new RuntimeException("Preset not found"));

        if (name != null && !name.trim().isEmpty()) {
            preset.setName(name.trim());
        }
        if (displayOrder != null) {
            preset.setDisplayOrder(displayOrder);
        }
        presetRepository.save(preset);

        if (items != null) {
            if (items.isEmpty()) {
                throw new IllegalArgumentException("Preset must contain at least one medicine");
            }
            itemRepository.deleteByPresetId(id);
            saveItems(id, items);
        }
        return preset;
    }

    public void deletePreset(Long id) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        PrescriptionPreset preset = presetRepository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new RuntimeException("Preset not found"));
        preset.setIsActive(false);
        presetRepository.save(preset);
    }

    private void saveItems(Long presetId, List<PrescriptionPresetItem> items) {
        int order = 0;
        for (PrescriptionPresetItem item : items) {
            PrescriptionPresetItem toSave = new PrescriptionPresetItem();
            toSave.setPresetId(presetId);
            toSave.setMedicineName(item.getMedicineName());
            toSave.setDosage(item.getDosage());
            toSave.setFrequency(item.getFrequency());
            toSave.setDuration(item.getDuration());
            toSave.setInstructions(item.getInstructions());
            toSave.setSortOrder(order++);
            itemRepository.save(toSave);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=PrescriptionPresetServiceTest -q`
Expected: PASS (9 tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hms/dto/PrescriptionPresetItemDTO.java backend/src/main/java/com/hms/dto/PrescriptionPresetDTO.java backend/src/main/java/com/hms/service/hospital/PrescriptionPresetService.java backend/src/test/java/com/hms/service/hospital/PrescriptionPresetServiceTest.java
git commit -m "Add PrescriptionPresetService with hospital-scoped CRUD for multi-item presets"
```

Stage ONLY these four files.

---

## Task 3: `PrescriptionPresetController` with tests

**Files:**
- Create: `backend/src/main/java/com/hms/controller/hospital/PrescriptionPresetController.java`
- Test: `backend/src/test/java/com/hms/controller/hospital/PrescriptionPresetControllerTest.java` (new)

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/hms/controller/hospital/PrescriptionPresetControllerTest.java`:

```java
package com.hms.controller.hospital;

import com.hms.dto.PrescriptionPresetItemDTO;
import com.hms.entity.PrescriptionPreset;
import com.hms.security.JwtUtil;
import com.hms.service.AuditLogService;
import com.hms.service.hospital.PrescriptionPresetService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(PrescriptionPresetController.class)
@Import(PrescriptionPresetControllerTest.MethodSecurityTestConfig.class)
class PrescriptionPresetControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean private PrescriptionPresetService presetService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private AuditLogService auditLogService;

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void listPresets_returnsOkForHospitalAdmin() throws Exception {
        when(presetService.listPresets()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/hospital/prescription-presets").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void listPresets_returnsOkForDoctor() throws Exception {
        when(presetService.listPresets()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/hospital/prescription-presets").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void listPresets_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(get("/hospital/prescription-presets").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void createPreset_returnsOkWhenServiceSucceeds() throws Exception {
        PrescriptionPreset saved = new PrescriptionPreset();
        saved.setId(1L);
        saved.setName("Fever Protocol");
        saved.setDisplayOrder(0);
        when(presetService.createPreset(eq("Fever Protocol"), anyList())).thenReturn(saved);
        when(presetService.getItems(1L)).thenReturn(Collections.emptyList());

        mockMvc.perform(post("/hospital/prescription-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Fever Protocol\",\"items\":[{\"medicineName\":\"Paracetamol\",\"dosage\":\"500mg\"}]}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Fever Protocol"));
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void createPreset_returnsBadRequestWhenServiceThrows() throws Exception {
        when(presetService.createPreset(anyString(), anyList()))
                .thenThrow(new IllegalArgumentException("Preset name is required"));

        mockMvc.perform(post("/hospital/prescription-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"items\":[]}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void createPreset_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(post("/hospital/prescription-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Fever Protocol\",\"items\":[]}")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void updatePreset_returnsOkWhenFound() throws Exception {
        PrescriptionPreset updated = new PrescriptionPreset();
        updated.setId(5L);
        updated.setName("Updated Name");
        when(presetService.updatePreset(eq(5L), eq("Updated Name"), any(), any())).thenReturn(updated);
        when(presetService.getItems(5L)).thenReturn(Collections.emptyList());

        mockMvc.perform(put("/hospital/prescription-presets/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Name\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"));
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void updatePreset_returnsBadRequestWhenNotFound() throws Exception {
        when(presetService.updatePreset(eq(999L), any(), any(), any()))
                .thenThrow(new RuntimeException("Preset not found"));

        mockMvc.perform(put("/hospital/prescription-presets/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\"}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void deletePreset_returnsOkWhenSuccessful() throws Exception {
        mockMvc.perform(delete("/hospital/prescription-presets/7").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void deletePreset_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(delete("/hospital/prescription-presets/7").with(csrf()))
                .andExpect(status().isForbidden());
    }
}
```

If, when you run this, Spring complains about missing beans beyond `JwtUtil`/`AuditLogService`, add the additional `@MockBean` fields needed — the error message will name the missing bean type directly.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=PrescriptionPresetControllerTest -q`
Expected: FAIL (compile error) — `PrescriptionPresetController` doesn't exist yet.

- [ ] **Step 3: Create the controller**

Create `backend/src/main/java/com/hms/controller/hospital/PrescriptionPresetController.java`:

```java
package com.hms.controller.hospital;

import com.hms.dto.PrescriptionPresetDTO;
import com.hms.dto.PrescriptionPresetItemDTO;
import com.hms.entity.PrescriptionPreset;
import com.hms.entity.PrescriptionPresetItem;
import com.hms.service.hospital.PrescriptionPresetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/hospital/prescription-presets")
public class PrescriptionPresetController {

    @Autowired
    private PrescriptionPresetService presetService;

    private PrescriptionPresetItemDTO toItemDto(PrescriptionPresetItem i) {
        return new PrescriptionPresetItemDTO(i.getId(), i.getMedicineName(), i.getDosage(), i.getFrequency(), i.getDuration(), i.getInstructions());
    }

    private PrescriptionPresetDTO toDto(PrescriptionPreset p) {
        List<PrescriptionPresetItemDTO> items = presetService.getItems(p.getId()).stream()
                .map(this::toItemDto)
                .collect(Collectors.toList());
        return new PrescriptionPresetDTO(p.getId(), p.getName(), items, p.getDisplayOrder());
    }

    private PrescriptionPresetItem toItemEntity(PrescriptionPresetItemDTO dto) {
        PrescriptionPresetItem item = new PrescriptionPresetItem();
        item.setMedicineName(dto.getMedicineName());
        item.setDosage(dto.getDosage());
        item.setFrequency(dto.getFrequency());
        item.setDuration(dto.getDuration());
        item.setInstructions(dto.getInstructions());
        return item;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> listPresets() {
        List<PrescriptionPresetDTO> dtos = presetService.listPresets().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> createPreset(@RequestBody PrescriptionPresetDTO dto) {
        try {
            List<PrescriptionPresetItem> items = dto.getItems() == null ? Collections.emptyList() : dto.getItems().stream()
                    .map(this::toItemEntity)
                    .collect(Collectors.toList());
            PrescriptionPreset saved = presetService.createPreset(dto.getName(), items);
            return ResponseEntity.ok(toDto(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> updatePreset(@PathVariable Long id, @RequestBody PrescriptionPresetDTO dto) {
        try {
            List<PrescriptionPresetItem> items = dto.getItems() == null ? null : dto.getItems().stream()
                    .map(this::toItemEntity)
                    .collect(Collectors.toList());
            PrescriptionPreset saved = presetService.updatePreset(id, dto.getName(), items, dto.getDisplayOrder());
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

Run: `cd backend && mvn test -Dtest=PrescriptionPresetControllerTest -q`
Expected: PASS (10 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/controller/hospital/PrescriptionPresetController.java backend/src/test/java/com/hms/controller/hospital/PrescriptionPresetControllerTest.java
git commit -m "Add PrescriptionPresetController with role-gated CRUD endpoints"
```

---

## Task 4: Database migration + canonical schema update

**Files:**
- Modify: `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`
- Modify: `setup/schema-full.sql`

- [ ] **Step 1: Add the migration method**

In `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`, add a new call at the end of `runMigrations()`'s call list (currently ending with `ensureConsultationNotePresetsTable();`):

```java
        ensureConsultationNotePresetsTable();
        ensurePrescriptionPresetTables(); // NEW
```

Add the method itself, following the exact style of `ensureConsultationNotePresetsTable()` in the same file — this one creates TWO tables (parent + child), so check/create each independently:

```java
    /**
     * Creates the prescription_presets and prescription_preset_items tables
     * if they do not exist. Stores per-hospital named bundles of medicines
     * a doctor can apply to a prescription in one action.
     * ddl-auto=update cannot create tables from scratch — this runner
     * bridges that gap.
     */
    private void ensurePrescriptionPresetTables() {
        try {
            Integer presetCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prescription_presets'",
                Integer.class
            );
            if (presetCount != null && presetCount == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE prescription_presets (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  name VARCHAR(150) NOT NULL," +
                    "  display_order INT NOT NULL DEFAULT 0," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: prescription_presets table created");
            }

            Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prescription_preset_items'",
                Integer.class
            );
            if (itemCount != null && itemCount == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE prescription_preset_items (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  preset_id BIGINT NOT NULL," +
                    "  medicine_name VARCHAR(255) NOT NULL," +
                    "  dosage VARCHAR(50) DEFAULT NULL," +
                    "  frequency VARCHAR(50) DEFAULT NULL," +
                    "  duration VARCHAR(50) DEFAULT NULL," +
                    "  instructions VARCHAR(200) DEFAULT NULL," +
                    "  sort_order INT NOT NULL DEFAULT 0," +
                    "  PRIMARY KEY (id)," +
                    "  FOREIGN KEY (preset_id) REFERENCES prescription_presets(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: prescription_preset_items table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (prescription presets): {}", e.getMessage());
        }
    }
```

- [ ] **Step 2: Update the canonical schema**

In `setup/schema-full.sql`, add both table definitions near the `consultation_note_presets` table (added in the previous feature) for readability:

```sql
CREATE TABLE `prescription_presets` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `hospital_id` bigint NOT NULL,
  `name` varchar(150) NOT NULL,
  `display_order` int NOT NULL DEFAULT '0',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK_prescription_presets_hospital` (`hospital_id`),
  CONSTRAINT `FK_prescription_presets_hospital` FOREIGN KEY (`hospital_id`) REFERENCES `hospitals` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `prescription_preset_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `preset_id` bigint NOT NULL,
  `medicine_name` varchar(255) NOT NULL,
  `dosage` varchar(50) DEFAULT NULL,
  `frequency` varchar(50) DEFAULT NULL,
  `duration` varchar(50) DEFAULT NULL,
  `instructions` varchar(200) DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `FK_prescription_preset_items_preset` (`preset_id`),
  CONSTRAINT `FK_prescription_preset_items_preset` FOREIGN KEY (`preset_id`) REFERENCES `prescription_presets` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- [ ] **Step 3: Compile check**

Run: `cd backend && mvn -q -o clean compile`
Expected: no output = success.

- [ ] **Step 4: Run full backend test suite**

Run: `cd backend && mvn test -q`
Expected: BUILD SUCCESS, all tests pass including the new `PrescriptionPresetServiceTest` and `PrescriptionPresetControllerTest`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java setup/schema-full.sql
git commit -m "Add DB migration for prescription_presets and prescription_preset_items tables"
```

---

## Task 5: Backend live verification against a real database

No automated test — verifies the migration and full request/response cycle against the actual dev database.

**Files:** none (verification only)

- [ ] **Step 1: Restart the backend**

Stop whatever backend process is currently running (`netstat -ano | grep :8080`, then stop that PID), then:

```bash
cd backend && (mvn -q spring-boot:run > /tmp/rx-preset-verify.log 2>&1 &)
```

Wait for `Started HospitalManagementSystemApplication`, then check:

```bash
grep "prescription_preset" /tmp/rx-preset-verify.log
```

Expected: both "DB migration applied: prescription_presets table created" and "...prescription_preset_items table created" (or no output if both already existed from a prior run of this exact migration).

- [ ] **Step 2: Verify the schema directly**

```bash
mysql -u root -p -D <db_name> -e "DESCRIBE prescription_presets; DESCRIBE prescription_preset_items;"
```

- [ ] **Step 3: Verify the API end-to-end**

Craft a JWT for a test hospital admin or doctor. Create a preset with two items:

```bash
curl -s -X POST "http://localhost:8080/hospital/prescription-presets" -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"Fever Protocol","items":[{"medicineName":"Paracetamol","dosage":"500mg","frequency":"1-0-1","duration":"5 Days","instructions":"After food"},{"medicineName":"Cetirizine","dosage":"10mg","frequency":"0-0-1","duration":"5 Days"}]}'
```

Expected: `200 OK`, response includes `"name":"Fever Protocol"` and `"items"` array with both medicines.

List it back:

```bash
curl -s "http://localhost:8080/hospital/prescription-presets" -H "Authorization: Bearer <token>"
```

Expected: `200 OK`, array containing the preset with both items nested.

Update it (replace items with just one):

```bash
curl -s -X PUT "http://localhost:8080/hospital/prescription-presets/<id>" -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"Fever Protocol","items":[{"medicineName":"Paracetamol","dosage":"650mg"}]}'
```

Expected: `200 OK`, `"items"` array now has exactly one entry with `"dosage":"650mg"`.

Delete it:

```bash
curl -s -X DELETE "http://localhost:8080/hospital/prescription-presets/<id>" -H "Authorization: Bearer <token>"
```

Expected: `200 OK`. Re-list — the deleted preset should no longer appear.

---

## Task 6: Frontend — `hospitalService.js` API functions

**Files:**
- Modify: `frontend/src/services/hospitalService.js`

- [ ] **Step 1: Add the four functions**

In `frontend/src/services/hospitalService.js`, add these functions near the `getConsultationNotePresets`/`createConsultationNotePreset`/`updateConsultationNotePreset`/`deleteConsultationNotePreset` group added in the previous feature (same file, same object) — read that section first to confirm exact placement, then add:

```javascript
    getPrescriptionPresets: async () => {
        const response = await apiClient.get('/hospital/prescription-presets');
        return response.data;
    },

    createPrescriptionPreset: async (data) => {
        const response = await apiClient.post('/hospital/prescription-presets', data);
        return response.data;
    },

    updatePrescriptionPreset: async (id, data) => {
        const response = await apiClient.put(`/hospital/prescription-presets/${id}`, data);
        return response.data;
    },

    deletePrescriptionPreset: async (id) => {
        const response = await apiClient.delete(`/hospital/prescription-presets/${id}`);
        return response.data;
    },
```

- [ ] **Step 2: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/services/hospitalService.js
git commit -m "Add prescription preset API functions"
```

---

## Task 7: Frontend — `PrescriptionPresetsManager.jsx` (list/create/edit/delete/reorder)

**Files:**
- Create: `frontend/src/components/PrescriptionPresetsManager.jsx`

This is a self-contained, presentational component with no modal chrome — used two ways: wrapped in a modal (Task 8) and rendered full-page under a new Admin tab (Task 10). Unlike `NotePresetsManager.jsx`, each preset here holds a list of medicine rows, so create/edit needs a small inline multi-row form.

- [ ] **Step 1: Create the component**

Create `frontend/src/components/PrescriptionPresetsManager.jsx`:

```jsx
import React, { useState, useEffect, useCallback } from 'react';
import hospitalService from '../services/hospitalService';
import { useToast } from '../context/ToastContext';
import ConfirmationModal from './ConfirmationModal';

const EMPTY_ITEM = { medicineName: '', dosage: '', frequency: '', duration: '', instructions: '' };

/**
 * Lists, creates, edits, deletes, and reorders prescription presets (each a
 * named bundle of one or more medicine rows). Self-contained: does its own
 * data fetching, so it can be dropped into a modal or a full page.
 */
const PrescriptionPresetsManager = () => {
    const { success, error: toastError } = useToast();
    const [presets, setPresets] = useState([]);
    const [loading, setLoading] = useState(true);
    const [deleteConfirm, setDeleteConfirm] = useState({ isOpen: false, id: null });

    // Form state shared by "create new" and "edit existing" — editingId is
    // null while creating, or the preset's id while editing.
    const [formOpen, setFormOpen] = useState(false);
    const [editingId, setEditingId] = useState(null);
    const [formName, setFormName] = useState('');
    const [formItems, setFormItems] = useState([{ ...EMPTY_ITEM }]);
    const [saving, setSaving] = useState(false);

    const loadPresets = useCallback(async () => {
        setLoading(true);
        try {
            const data = await hospitalService.getPrescriptionPresets();
            setPresets(data || []);
        } catch (err) {
            toastError('Failed to load prescription presets');
        } finally {
            setLoading(false);
        }
    }, [toastError]);

    useEffect(() => {
        loadPresets();
    }, [loadPresets]);

    const openCreateForm = () => {
        setEditingId(null);
        setFormName('');
        setFormItems([{ ...EMPTY_ITEM }]);
        setFormOpen(true);
    };

    const openEditForm = (preset) => {
        setEditingId(preset.id);
        setFormName(preset.name);
        setFormItems(preset.items && preset.items.length > 0 ? preset.items.map(i => ({ ...i })) : [{ ...EMPTY_ITEM }]);
        setFormOpen(true);
    };

    const closeForm = () => {
        setFormOpen(false);
        setEditingId(null);
    };

    const updateFormItem = (index, field, value) => {
        setFormItems(prev => prev.map((it, i) => (i === index ? { ...it, [field]: value } : it)));
    };

    const addFormItemRow = () => {
        setFormItems(prev => [...prev, { ...EMPTY_ITEM }]);
    };

    const removeFormItemRow = (index) => {
        setFormItems(prev => prev.filter((_, i) => i !== index));
    };

    const handleSaveForm = async (e) => {
        e.preventDefault();
        const cleanItems = formItems
            .filter(it => it.medicineName.trim())
            .map(it => ({ ...it, medicineName: it.medicineName.trim() }));
        if (!formName.trim() || cleanItems.length === 0) return;

        setSaving(true);
        try {
            if (editingId) {
                const updated = await hospitalService.updatePrescriptionPreset(editingId, { name: formName.trim(), items: cleanItems });
                setPresets(prev => prev.map(p => (p.id === editingId ? updated : p)));
                success('Preset updated');
            } else {
                const created = await hospitalService.createPrescriptionPreset({ name: formName.trim(), items: cleanItems });
                setPresets(prev => [...prev, created]);
                success('Preset created');
            }
            closeForm();
        } catch (err) {
            toastError(err?.response?.data || 'Failed to save preset');
        } finally {
            setSaving(false);
        }
    };

    const handleDelete = (id) => {
        setDeleteConfirm({ isOpen: true, id });
    };

    const confirmDelete = async () => {
        const id = deleteConfirm.id;
        try {
            await hospitalService.deletePrescriptionPreset(id);
            setPresets(prev => prev.filter(p => p.id !== id));
            success('Preset deleted');
        } catch (err) {
            toastError('Failed to delete preset');
        }
    };

    const handleMove = async (index, direction) => {
        const targetIndex = index + direction;
        if (targetIndex < 0 || targetIndex >= presets.length) return;

        const a = presets[index];
        const b = presets[targetIndex];
        try {
            const [updatedA, updatedB] = await Promise.all([
                hospitalService.updatePrescriptionPreset(a.id, { displayOrder: b.displayOrder }),
                hospitalService.updatePrescriptionPreset(b.id, { displayOrder: a.displayOrder }),
            ]);
            const next = [...presets];
            next[index] = updatedB;
            next[targetIndex] = updatedA;
            setPresets(next);
        } catch (err) {
            toastError('Failed to reorder presets');
            loadPresets();
        }
    };

    return (
        <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm space-y-6">
            <div className="flex justify-between items-start">
                <div>
                    <h3 className="text-lg font-semibold text-gray-900 mb-1">Prescription Presets</h3>
                    <p className="text-xs text-gray-500">
                        Named bundles of medicines a doctor can apply to a prescription in one click.
                    </p>
                </div>
                {!formOpen && (
                    <button
                        type="button"
                        onClick={openCreateForm}
                        className="bg-gray-950 text-white text-xs font-semibold px-4 py-2 rounded-lg hover:bg-gray-800 transition"
                    >
                        + Create Preset
                    </button>
                )}
            </div>

            {formOpen && (
                <form onSubmit={handleSaveForm} className="bg-slate-50 p-4 rounded-xl border border-gray-200 space-y-3">
                    <input
                        type="text"
                        value={formName}
                        onChange={(e) => setFormName(e.target.value)}
                        placeholder="Preset name (e.g. Fever Protocol)"
                        maxLength={150}
                        className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm font-semibold focus:ring-2 focus:ring-primary-500 focus:border-transparent outline-none"
                    />

                    {formItems.map((item, index) => (
                        <div key={index} className="grid grid-cols-2 gap-2 bg-white p-3 rounded-lg border border-gray-200">
                            <input
                                type="text"
                                value={item.medicineName}
                                onChange={(e) => updateFormItem(index, 'medicineName', e.target.value)}
                                placeholder="Medicine name"
                                className="col-span-2 border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
                            />
                            <input
                                type="text"
                                value={item.dosage}
                                onChange={(e) => updateFormItem(index, 'dosage', e.target.value)}
                                placeholder="Dosage (e.g. 500mg)"
                                className="border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
                            />
                            <input
                                type="text"
                                value={item.frequency}
                                onChange={(e) => updateFormItem(index, 'frequency', e.target.value)}
                                placeholder="Frequency (e.g. 1-0-1)"
                                className="border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
                            />
                            <input
                                type="text"
                                value={item.duration}
                                onChange={(e) => updateFormItem(index, 'duration', e.target.value)}
                                placeholder="Duration (e.g. 5 Days)"
                                className="border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
                            />
                            <input
                                type="text"
                                value={item.instructions}
                                onChange={(e) => updateFormItem(index, 'instructions', e.target.value)}
                                placeholder="Instructions (e.g. After food)"
                                className="border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
                            />
                            {formItems.length > 1 && (
                                <button
                                    type="button"
                                    onClick={() => removeFormItemRow(index)}
                                    className="col-span-2 text-xs text-red-600 hover:text-red-800 text-left"
                                >
                                    Remove this medicine
                                </button>
                            )}
                        </div>
                    ))}

                    <button
                        type="button"
                        onClick={addFormItemRow}
                        className="text-xs text-indigo-600 hover:text-indigo-800 font-medium"
                    >
                        + Add another medicine
                    </button>

                    <div className="flex gap-2 pt-2">
                        <button
                            type="submit"
                            disabled={saving || !formName.trim() || !formItems.some(it => it.medicineName.trim())}
                            className="bg-primary-600 text-white text-sm font-semibold px-4 py-2 rounded-lg hover:bg-primary-700 transition disabled:opacity-50"
                        >
                            {editingId ? 'Save Changes' : 'Create Preset'}
                        </button>
                        <button
                            type="button"
                            onClick={closeForm}
                            className="text-sm text-gray-600 hover:text-gray-800 px-4 py-2"
                        >
                            Cancel
                        </button>
                    </div>
                </form>
            )}

            {loading ? (
                <p className="text-sm text-gray-500">Loading...</p>
            ) : presets.length === 0 ? (
                <div className="text-center py-8 border border-dashed border-gray-200 rounded-xl bg-gray-50">
                    <p className="text-sm text-gray-500">No prescription presets yet. Create your first one above.</p>
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

                            <div className="flex-1">
                                <div className="text-sm font-semibold text-gray-800">{preset.name}</div>
                                <div className="text-xs text-gray-500 mt-0.5">
                                    {(preset.items || []).map(i => i.medicineName).join(', ') || 'No medicines'}
                                </div>
                            </div>

                            <div className="flex gap-2 text-sm">
                                <button onClick={() => openEditForm(preset)} className="text-indigo-600 hover:text-indigo-900 font-medium">Edit</button>
                                <button onClick={() => handleDelete(preset.id)} className="text-red-600 hover:text-red-900 font-medium">Delete</button>
                            </div>
                        </div>
                    ))}
                </div>
            )}

            <ConfirmationModal
                isOpen={deleteConfirm.isOpen}
                title="Delete Prescription Preset"
                message="Are you sure you want to delete this preset? It will no longer be available to apply during a consultation."
                onConfirm={confirmDelete}
                onCancel={() => setDeleteConfirm({ isOpen: false, id: null })}
            />
        </div>
    );
};

export default PrescriptionPresetsManager;
```

- [ ] **Step 2: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 3: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/PrescriptionPresetsManager.jsx
git commit -m "Add PrescriptionPresetsManager component for multi-item preset CRUD"
```

---

## Task 8: Frontend — `ManagePrescriptionPresetsModal.jsx` (modal wrapper)

**Files:**
- Create: `frontend/src/components/ManagePrescriptionPresetsModal.jsx`

- [ ] **Step 1: Create the component**

Create `frontend/src/components/ManagePrescriptionPresetsModal.jsx` (mirrors `ManageNotePresetsModal.jsx` exactly, including its Escape-key and initial-focus accessibility handling):

```jsx
import React, { useEffect, useRef } from 'react';
import PrescriptionPresetsManager from './PrescriptionPresetsManager';

/**
 * Modal wrapper around PrescriptionPresetsManager, used from
 * ConsultationModal so a doctor can manage prescription presets without
 * leaving the consultation screen.
 */
const ManagePrescriptionPresetsModal = ({ isOpen, onClose }) => {
    const closeBtnRef = useRef(null);

    useEffect(() => {
        if (isOpen) {
            setTimeout(() => closeBtnRef.current?.focus(), 50);
        }
    }, [isOpen]);

    useEffect(() => {
        if (!isOpen) return;
        const handleKeyDown = (e) => {
            if (e.key === 'Escape') onClose();
        };
        document.addEventListener('keydown', handleKeyDown);
        return () => document.removeEventListener('keydown', handleKeyDown);
    }, [isOpen, onClose]);

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
                aria-labelledby="manage-prescription-presets-title"
                className="bg-gray-50 rounded-2xl shadow-2xl w-full max-w-2xl max-h-[85vh] overflow-y-auto"
                onClick={(e) => e.stopPropagation()}
            >
                <div className="flex justify-between items-center p-4 border-b border-gray-200 bg-white rounded-t-2xl sticky top-0">
                    <h2 id="manage-prescription-presets-title" className="text-base font-bold text-gray-900">Manage Prescription Presets</h2>
                    <button ref={closeBtnRef} onClick={onClose} className="text-gray-400 hover:text-gray-700 text-xl leading-none" aria-label="Close">
                        &times;
                    </button>
                </div>
                <div className="p-4">
                    <PrescriptionPresetsManager />
                </div>
            </div>
        </div>
    );
};

export default ManagePrescriptionPresetsModal;
```

- [ ] **Step 2: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 3: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/ManagePrescriptionPresetsModal.jsx
git commit -m "Add ManagePrescriptionPresetsModal wrapper for consultation-screen use"
```

---

## Task 9: Frontend — editable prescription rows + preset dropdown in `ConsultationModal.jsx`

**Files:**
- Modify: `frontend/src/components/ConsultationModal.jsx`

This is the largest frontend task: it adds row-editing to the existing prescription list (previously remove-only), plus the preset dropdown, "Save as Preset", and "Manage Presets" link.

- [ ] **Step 1: Add imports and state**

Add to the imports at the top, alongside the existing `ManageNotePresetsModal` import (`frontend/src/components/ConsultationModal.jsx:8`):

```javascript
import ManagePrescriptionPresetsModal from './ManagePrescriptionPresetsModal';
```

Add new state near `notePresets`/`showManagePresets` (`frontend/src/components/ConsultationModal.jsx:18-19`):

```javascript
    const [prescriptionPresets, setPrescriptionPresets] = useState([]);
    const [showManagePrescriptionPresets, setShowManagePrescriptionPresets] = useState(false);
    const [editingMedicineIndex, setEditingMedicineIndex] = useState(null);
```

- [ ] **Step 2: Fetch prescription presets when the modal opens**

Add a new `useEffect`, placed immediately after the note-presets-fetching `useEffect` added in the previous feature (the one that calls `hospitalService.getConsultationNotePresets('TREATMENT_NOTES')` on `[isOpen]`):

```javascript
    useEffect(() => {
        if (isOpen) {
            hospitalService.getPrescriptionPresets()
                .then(data => setPrescriptionPresets(data || []))
                .catch(() => setPrescriptionPresets([]));
        }
    }, [isOpen]);
```

- [ ] **Step 3: Make `handleAddMedicine` dual-purpose (add new OR save an edit)**

Find the current `handleAddMedicine` (`frontend/src/components/ConsultationModal.jsx:233-245`):

```javascript
    const handleAddMedicine = () => {
        setFormData(prev => ({
            ...prev,
            prescription: [...prev.prescription, newMedicine]
        }));
        setNewMedicine({
            medicineName: '',
            dosage: '',
            frequency: '',
            duration: '',
            instructions: ''
        });
    };
```

Replace it with:

```javascript
    const handleAddMedicine = () => {
        setFormData(prev => {
            if (editingMedicineIndex !== null) {
                const updated = [...prev.prescription];
                updated[editingMedicineIndex] = newMedicine;
                return { ...prev, prescription: updated };
            }
            return { ...prev, prescription: [...prev.prescription, newMedicine] };
        });
        setNewMedicine({
            medicineName: '',
            dosage: '',
            frequency: '',
            duration: '',
            instructions: ''
        });
        setEditingMedicineIndex(null);
    };

    const handleEditMedicine = (index) => {
        setNewMedicine(formData.prescription[index]);
        setEditingMedicineIndex(index);
    };

    const handleCancelEditMedicine = () => {
        setNewMedicine({
            medicineName: '',
            dosage: '',
            frequency: '',
            duration: '',
            instructions: ''
        });
        setEditingMedicineIndex(null);
    };

    const handleApplyPrescriptionPreset = (presetId) => {
        if (!presetId) return;
        const preset = prescriptionPresets.find(p => String(p.id) === String(presetId));
        if (!preset || !preset.items || preset.items.length === 0) return;
        const itemsToAdd = preset.items.map(({ medicineName, dosage, frequency, duration, instructions }) => ({
            medicineName, dosage: dosage || '', frequency: frequency || '', duration: duration || '', instructions: instructions || ''
        }));
        setFormData(prev => ({
            ...prev,
            prescription: [...prev.prescription, ...itemsToAdd]
        }));
    };

    const handleSaveCurrentAsPreset = async () => {
        if (formData.prescription.length === 0) {
            toastError('Add at least one medicine before saving a preset');
            return;
        }
        const name = window.prompt('Name this preset (e.g. "Fever Protocol"):');
        if (!name || !name.trim()) return;
        try {
            await hospitalService.createPrescriptionPreset({ name: name.trim(), items: formData.prescription });
            success('Preset saved');
            const data = await hospitalService.getPrescriptionPresets();
            setPrescriptionPresets(data || []);
        } catch (err) {
            toastError(err?.response?.data || 'Failed to save preset');
        }
    };
```

Also update `handleRemoveMedicine` (`frontend/src/components/ConsultationModal.jsx:247-252`) so removing the row currently being edited also clears the edit form — find:

```javascript
    const handleRemoveMedicine = (index) => {
        setFormData(prev => ({
            ...prev,
            prescription: prev.prescription.filter((_, i) => i !== index)
        }));
    };
```

Replace with:

```javascript
    const handleRemoveMedicine = (index) => {
        setFormData(prev => ({
            ...prev,
            prescription: prev.prescription.filter((_, i) => i !== index)
        }));
        if (editingMedicineIndex === index) {
            handleCancelEditMedicine();
        }
    };
```

Leave `handleRemoveMedicine` in its original location in the file (do not move it) — only change its body as shown above. It's fine for this edit to reference `handleCancelEditMedicine` even though that function is defined earlier in the same component body: both are `const` arrow functions created fresh on every render, and `handleRemoveMedicine` is only ever *called* later from a click handler, by which point the whole component function has finished running once and `handleCancelEditMedicine` already exists in scope.

- [ ] **Step 4: Render the preset dropdown + Save/Manage links above the prescription list**

Find the "Prescription List" section start (`frontend/src/components/ConsultationModal.jsx:958-963`):

```jsx
                            ) : (
                                <div className="space-y-4">
                                    {/* Prescription List */}
                                    {formData.prescription.length > 0 && (
                                        <div className="mb-6">
                                            <h4 className="text-sm font-semibold text-gray-700 mb-3">Prescribed Medicines ({formData.prescription.length})</h4>
```

Replace with (adds the dropdown/actions row right before the list, unconditionally so it's visible even with an empty prescription):

```jsx
                            ) : (
                                <div className="space-y-4">
                                    {/* Prescription Presets */}
                                    <div className="flex flex-wrap items-center gap-2 bg-slate-50 p-3 rounded-lg border border-gray-200">
                                        <select
                                            onChange={(e) => { handleApplyPrescriptionPreset(e.target.value); e.target.value = ''; }}
                                            defaultValue=""
                                            className="flex-1 min-w-[200px] border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent outline-none"
                                        >
                                            <option value="" disabled>Apply a prescription preset...</option>
                                            {prescriptionPresets.map(preset => (
                                                <option key={preset.id} value={preset.id}>{preset.name} ({(preset.items || []).length} medicine{(preset.items || []).length === 1 ? '' : 's'})</option>
                                            ))}
                                        </select>
                                        <button type="button" onClick={handleSaveCurrentAsPreset} className="text-xs text-gray-600 hover:text-gray-800 underline whitespace-nowrap">
                                            Save current as preset
                                        </button>
                                        <button type="button" onClick={() => setShowManagePrescriptionPresets(true)} className="text-xs text-gray-600 hover:text-gray-800 underline whitespace-nowrap">
                                            Manage Presets
                                        </button>
                                    </div>

                                    {/* Prescription List */}
                                    {formData.prescription.length > 0 && (
                                        <div className="mb-6">
                                            <h4 className="text-sm font-semibold text-gray-700 mb-3">Prescribed Medicines ({formData.prescription.length})</h4>
```

- [ ] **Step 5: Add an Edit action to each prescription row**

Find the medicine row block (`frontend/src/components/ConsultationModal.jsx:965-982`, now shifted a few lines later after Step 4's insertion — locate by its distinctive content):

```jsx
                                                {formData.prescription.map((med, index) => (
                                                    <div key={index} className="flex items-center justify-between bg-gray-50 p-3 rounded-lg border border-gray-200">
                                                        <div className="flex-1">
                                                            <div className="font-semibold text-gray-800">{med.medicineName}</div>
                                                            <div className="text-xs text-gray-500 mt-1">
                                                                {med.dosage} • {med.frequency} • {med.duration}
                                                                {med.instructions && ` • ${med.instructions}`}
                                                            </div>
                                                        </div>
                                                        <button
                                                            onClick={() => handleRemoveMedicine(index)}
                                                            className="ml-3 text-gray-500 hover:text-gray-700"
                                                        >
                                                            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                                                            </svg>
                                                        </button>
                                                    </div>
                                                ))}
```

Replace with (adds an Edit pencil button before the remove ×, and highlights the row currently being edited):

```jsx
                                                {formData.prescription.map((med, index) => (
                                                    <div key={index} className={`flex items-center justify-between p-3 rounded-lg border ${editingMedicineIndex === index ? 'bg-primary-50 border-primary-300' : 'bg-gray-50 border-gray-200'}`}>
                                                        <div className="flex-1">
                                                            <div className="font-semibold text-gray-800">{med.medicineName}</div>
                                                            <div className="text-xs text-gray-500 mt-1">
                                                                {med.dosage} • {med.frequency} • {med.duration}
                                                                {med.instructions && ` • ${med.instructions}`}
                                                            </div>
                                                        </div>
                                                        <button
                                                            onClick={() => handleEditMedicine(index)}
                                                            className="ml-3 text-indigo-600 hover:text-indigo-800 text-xs font-semibold"
                                                        >
                                                            Edit
                                                        </button>
                                                        <button
                                                            onClick={() => handleRemoveMedicine(index)}
                                                            className="ml-3 text-gray-500 hover:text-gray-700"
                                                        >
                                                            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                                                            </svg>
                                                        </button>
                                                    </div>
                                                ))}
```

- [ ] **Step 6: Update the "Add Medicine" form's heading and submit button to reflect edit mode**

Find (`frontend/src/components/ConsultationModal.jsx:988-989`, shifted similarly):

```jsx
                                    <div className="bg-gray-50 p-4 rounded-lg border border-gray-200">
                                        <h4 className="text-sm font-semibold text-gray-700 mb-3">Add Medicine</h4>
```

Replace with:

```jsx
                                    <div className="bg-gray-50 p-4 rounded-lg border border-gray-200">
                                        <h4 className="text-sm font-semibold text-gray-700 mb-3">{editingMedicineIndex !== null ? 'Edit Medicine' : 'Add Medicine'}</h4>
```

Find the "+ Add Medicine" submit button (`frontend/src/components/ConsultationModal.jsx:1036-1042`):

```jsx
                                        <button
                                            onClick={handleAddMedicine}
                                            disabled={!newMedicine.medicineName}
                                            className="mt-3 w-full bg-primary-600 text-white py-2 rounded-lg hover:bg-primary-700 transition disabled:bg-gray-300 disabled:cursor-not-allowed text-sm font-semibold"
                                        >
                                            + Add Medicine
                                        </button>
```

Replace with:

```jsx
                                        <div className="mt-3 flex gap-2">
                                            <button
                                                onClick={handleAddMedicine}
                                                disabled={!newMedicine.medicineName}
                                                className="flex-1 bg-primary-600 text-white py-2 rounded-lg hover:bg-primary-700 transition disabled:bg-gray-300 disabled:cursor-not-allowed text-sm font-semibold"
                                            >
                                                {editingMedicineIndex !== null ? 'Save Changes' : '+ Add Medicine'}
                                            </button>
                                            {editingMedicineIndex !== null && (
                                                <button
                                                    onClick={handleCancelEditMedicine}
                                                    className="px-4 py-2 text-sm font-semibold text-gray-600 hover:text-gray-800"
                                                >
                                                    Cancel
                                                </button>
                                            )}
                                        </div>
```

- [ ] **Step 7: Render the manage-prescription-presets modal**

Find the `<ManageNotePresetsModal ... />` block added in the previous feature (search for `<ManageNotePresetsModal`), and add the new modal as a sibling immediately after its closing `/>`:

```jsx
                <ManagePrescriptionPresetsModal
                    isOpen={showManagePrescriptionPresets}
                    onClose={() => {
                        setShowManagePrescriptionPresets(false);
                        hospitalService.getPrescriptionPresets()
                            .then(data => setPrescriptionPresets(data || []))
                            .catch(() => {});
                    }}
                />
```

If any exact line numbers/content quoted in this task have drifted since this plan was written, locate the equivalent blocks by their distinctive content (the `Treatment Notes`/`ManageNotePresetsModal` blocks from the previous feature, the `Add Medicine` heading, the `+ Add Medicine` button, the medicine `.map` row) rather than trusting line numbers literally.

- [ ] **Step 8: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 9: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 10: Commit**

```bash
cd e:/Projects/HOSPITAL
git add frontend/src/components/ConsultationModal.jsx
git commit -m "Add editable prescription rows and preset dropdown to Prescription tab"
```

Stage ONLY this one file — do not run `git add -A` or `git add .`.

---

## Task 10: Frontend — "Prescription Presets" tab in Hospital Admin's Administration group

**Files:**
- Modify: `frontend/src/pages/hospital/HospitalAdminDashboard.jsx`

- [ ] **Step 1: Add the import**

Add near the `NotePresetsManager` import added in the previous feature:

```javascript
import PrescriptionPresetsManager from '../../components/PrescriptionPresetsManager';
```

- [ ] **Step 2: Add the tab entry**

Find the `quick-notes` tab entry added in the previous feature (search `id: 'quick-notes'`):

```javascript
        { id: 'quick-notes', label: 'Quick Notes', icon: null, requiredModule: null },
```

Add a new entry immediately after:

```javascript
        { id: 'quick-notes', label: 'Quick Notes', icon: null, requiredModule: null },
        { id: 'prescription-presets', label: 'Prescription Presets', icon: null, requiredModule: null },
```

- [ ] **Step 3: Add it to the Administration sidebar group**

Find `SIDEBAR_GROUPS`'s `group-administration` entry (search `group-administration`):

```javascript
        { id: 'group-administration', label: 'Administration', tabIds: ['settings', 'support', 'quick-notes'] },
```

Replace with:

```javascript
        { id: 'group-administration', label: 'Administration', tabIds: ['settings', 'support', 'quick-notes', 'prescription-presets'] },
```

- [ ] **Step 4: Render the tab's content**

Find the `activeTab === 'quick-notes'` block added in the previous feature (search `activeTab === 'quick-notes'`):

```jsx
                        {activeTab === 'quick-notes' && (
                            <div className="max-w-2xl mx-auto my-4">
                                <NotePresetsManager fieldType="TREATMENT_NOTES" />
                            </div>
                        )}
```

Add a new block immediately after it:

```jsx
                        {activeTab === 'quick-notes' && (
                            <div className="max-w-2xl mx-auto my-4">
                                <NotePresetsManager fieldType="TREATMENT_NOTES" />
                            </div>
                        )}

                        {activeTab === 'prescription-presets' && (
                            <div className="max-w-3xl mx-auto my-4">
                                <PrescriptionPresetsManager />
                            </div>
                        )}
```

If any exact content quoted above has drifted, locate the equivalent blocks by their distinctive content (`quick-notes` tab/group/render-block) rather than trusting exact text literally.

- [ ] **Step 5: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 6: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 7: Commit**

```bash
cd e:/Projects/HOSPITAL
git add frontend/src/pages/hospital/HospitalAdminDashboard.jsx
git commit -m "Add Prescription Presets tab under Hospital Admin Administration group"
```

---

## Task 11: Full-stack live verification

This project's established verification method: restart both servers cleanly, then drive the real UI with Playwright and inspect screenshots.

**Files:** none (verification only)

- [ ] **Step 1: Restart backend and frontend cleanly**

Same pattern as prior verification tasks: `mvn spring-boot:run` for backend, `npm run dev` for frontend, waiting for their respective ready log lines.

- [ ] **Step 2: Verify the "Prescription Presets" admin tab**

Using a Playwright script (craft a JWT for a `HOSPITAL_ADMIN` test user, navigate to `/hospital/admin?tab=prescription-presets`):
- Screenshot the empty state
- Click "+ Create Preset", fill in a name and one medicine row (name/dosage/frequency/duration/instructions), submit
- Screenshot — confirm the new preset appears with its medicine list summarized
- Click "Edit", add a second medicine row via "+ Add another medicine", change a field, save — confirm both medicines now show
- Click "Delete", confirm via the confirmation dialog — confirm it disappears

- [ ] **Step 3: Verify the Prescription tab in Consultation**

Navigate to a doctor's Consultation view for a patient with an active OPD/appointment, open the Prescription tab (not Clinical Notes):
- Screenshot — confirm the preset dropdown appears above the medicine list
- Select a preset from the dropdown — confirm its medicines are appended to the "Prescribed Medicines" list
- Click "Edit" on one of the appended rows — confirm the "Add Medicine" form pre-fills with that row's values and the button now reads "Save Changes"
- Change the dosage field and click "Save Changes" — confirm the row updates in place (not duplicated) and the form resets to "Add Medicine" mode
- Click "Save current as preset", provide a name via the prompt — confirm a new preset is created (verify via the admin tab or a follow-up API call)

- [ ] **Step 4: Verify role gating**

Confirm a `RECEPTIONIST`-role API call to `GET /hospital/prescription-presets` returns `403 Forbidden` via `curl`.

- [ ] **Step 5: Final full build and test check**

```bash
cd backend && mvn -q -o clean compile && mvn test -q
cd frontend && npx tsc --noEmit && npx vite build --mode development
```

Expected: both succeed with no errors, full backend test suite passes.

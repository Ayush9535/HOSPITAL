package com.hms.service.hospital;

import com.hms.entity.MedicineList;
import com.hms.repository.MedicineListRepository;
import com.hms.repository.MedicineRepository;
import com.hms.repository.MedicinePurchaseRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import com.hms.security.HospitalWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MedicineService global-catalog operations.
 * BUG-026: increases test coverage for platform Medicine catalog CRUD.
 */
@ExtendWith(MockitoExtension.class)
class MedicineServiceCatalogTest {

    @Mock MedicineListRepository medicineListRepository;
    @Mock MedicineRepository medicineRepository;
    @Mock MedicinePurchaseRepository medicinePurchaseRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock HospitalWebSocketHandler webSocketHandler;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks MedicineService medicineService;

    // ─── addCatalogMedicine ───────────────────────────────────────────────────

    @Test
    void addCatalogMedicine_savesAndReturnsWhenNameIsUnique() {
        MedicineList incoming = new MedicineList();
        incoming.setName("Amoxicillin 500mg");
        incoming.setType("Capsule");

        MedicineList saved = new MedicineList();
        saved.setId(1L);
        saved.setName("Amoxicillin 500mg");
        saved.setType("Capsule");

        when(medicineListRepository.existsByNameIgnoreCase("Amoxicillin 500mg")).thenReturn(false);
        when(medicineListRepository.save(incoming)).thenReturn(saved);

        MedicineList result = medicineService.addCatalogMedicine(incoming);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Amoxicillin 500mg");
        verify(medicineListRepository).save(incoming);
    }

    @Test
    void addCatalogMedicine_throwsIllegalArgumentWhenNameAlreadyExists() {
        MedicineList incoming = new MedicineList();
        incoming.setName("Paracetamol 500mg");

        when(medicineListRepository.existsByNameIgnoreCase("Paracetamol 500mg")).thenReturn(true);

        assertThatThrownBy(() -> medicineService.addCatalogMedicine(incoming))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Medicine already exists in catalog");

        verify(medicineListRepository, never()).save(any());
    }

    // ─── updateCatalogMedicine ────────────────────────────────────────────────

    @Test
    void updateCatalogMedicine_updatesFieldsWhenValidRequest() {
        MedicineList existing = new MedicineList();
        existing.setId(10L);
        existing.setName("Old Name");
        existing.setType("Tablet");

        MedicineList request = new MedicineList();
        request.setName("New Name");
        request.setType("Syrup");

        when(medicineListRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(medicineListRepository.existsByNameIgnoreCaseAndIdNot("New Name", 10L)).thenReturn(false);
        when(medicineListRepository.save(existing)).thenReturn(existing);

        MedicineList result = medicineService.updateCatalogMedicine(10L, request);

        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getType()).isEqualTo("Syrup");
        verify(medicineListRepository).save(existing);
    }

    @Test
    void updateCatalogMedicine_throwsWhenIdNotFound() {
        when(medicineListRepository.findById(999L)).thenReturn(Optional.empty());

        MedicineList request = new MedicineList();
        request.setName("Any Name");

        assertThatThrownBy(() -> medicineService.updateCatalogMedicine(999L, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Catalog medicine not found");
    }

    @Test
    void updateCatalogMedicine_throwsWhenNewNameConflictsWithAnotherEntry() {
        MedicineList existing = new MedicineList();
        existing.setId(10L);
        existing.setName("Ibuprofen");

        MedicineList request = new MedicineList();
        request.setName("Paracetamol"); // already taken by id=5

        when(medicineListRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(medicineListRepository.existsByNameIgnoreCaseAndIdNot("Paracetamol", 10L)).thenReturn(true);

        assertThatThrownBy(() -> medicineService.updateCatalogMedicine(10L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        verify(medicineListRepository, never()).save(any());
    }

    // ─── deleteCatalogMedicine ────────────────────────────────────────────────

    @Test
    void deleteCatalogMedicine_deletesWhenFound() {
        MedicineList existing = new MedicineList();
        existing.setId(20L);
        existing.setName("Cetirizine");

        when(medicineListRepository.findById(20L)).thenReturn(Optional.of(existing));

        medicineService.deleteCatalogMedicine(20L);

        verify(medicineListRepository).delete(existing);
    }

    @Test
    void deleteCatalogMedicine_throwsWhenNotFound() {
        when(medicineListRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> medicineService.deleteCatalogMedicine(404L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Catalog medicine not found");

        verify(medicineListRepository, never()).delete(any());
    }

    // ─── getPlatformMedicines ─────────────────────────────────────────────────

    @Test
    void getPlatformMedicines_delegatesToFindAllWhenQueryIsBlank() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by("name").ascending());
        Page<MedicineList> emptyPage = new PageImpl<>(List.of());

        when(medicineListRepository.findAll(pageable)).thenReturn(emptyPage);

        Page<MedicineList> result = medicineService.getPlatformMedicines("", pageable);

        assertThat(result).isEqualTo(emptyPage);
        verify(medicineListRepository).findAll(pageable);
        verify(medicineListRepository, never()).findByNameContainingIgnoreCase(any(), any());
    }

    @Test
    void getPlatformMedicines_delegatesToSearchWhenQueryIsNonBlank() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by("name").ascending());

        MedicineList med = new MedicineList();
        med.setId(1L);
        med.setName("Paracetamol 500mg");

        Page<MedicineList> resultPage = new PageImpl<>(List.of(med));
        when(medicineListRepository.findByNameContainingIgnoreCase("para", pageable)).thenReturn(resultPage);

        Page<MedicineList> result = medicineService.getPlatformMedicines("para", pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getName()).isEqualTo("Paracetamol 500mg");
        verify(medicineListRepository).findByNameContainingIgnoreCase("para", pageable);
        verify(medicineListRepository, never()).findAll(pageable);
    }
}

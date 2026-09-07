package com.hms.service.hospital;

import com.hms.entity.PrescriptionPreset;
import com.hms.entity.PrescriptionPresetItem;
import com.hms.repository.PrescriptionPresetItemRepository;
import com.hms.repository.PrescriptionPresetRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrescriptionPresetServiceTest {

    @Mock PrescriptionPresetRepository presetRepository;
    @Mock PrescriptionPresetItemRepository itemRepository;
    @Mock PresetOwnershipSupport ownership;
    @Mock SecurityContextHelper securityHelper;

    @InjectMocks PrescriptionPresetService service;

    // Tests exercise the admin path (admin manages any preset in the hospital).
    // Ownership/broadcast is delegated to PresetOwnershipSupport (mocked here).
    @BeforeEach
    void asAdmin() {
        when(ownership.isAdmin()).thenReturn(true);
    }

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
        when(presetRepository.findByHospitalIdAndPresetTypeAndIsActiveTrueOrderByDisplayOrderAsc(1L, "PRESCRIPTION"))
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

        assertThatThrownBy(() -> service.createPreset("   ", List.of(item("Paracetamol")), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void createPreset_noItems_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);

        assertThatThrownBy(() -> service.createPreset("Fever Protocol", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one medicine");
    }

    @Test
    void createPreset_valid_savesPresetAndItemsWithHospitalIdAndNextDisplayOrder() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(presetRepository.findByHospitalIdAndPresetTypeAndIsActiveTrueOrderByDisplayOrderAsc(1L, "PRESCRIPTION"))
                .thenReturn(List.of(new PrescriptionPreset())); // 1 existing
        when(presetRepository.save(any(PrescriptionPreset.class))).thenAnswer(inv -> {
            PrescriptionPreset p = inv.getArgument(0);
            p.setId(9L);
            return p;
        });
        when(itemRepository.save(any(PrescriptionPresetItem.class))).thenAnswer(inv -> inv.getArgument(0));

        PrescriptionPreset result = service.createPreset("  Fever Protocol  ", List.of(item("Paracetamol"), item("Cetirizine")), null);

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

        assertThatThrownBy(() -> service.updatePreset(99L, "New Name", null, null, null))
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

        PrescriptionPreset result = service.updatePreset(5L, "New Name", List.of(item("Ibuprofen")), 2, null);

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

        assertThatThrownBy(() -> service.updatePreset(5L, "Name", List.of(), null, null))
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

package com.hms.service.platform;

import com.hms.entity.InventoryMasterItem;
import com.hms.repository.InventoryMasterItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PlatformInventoryItemServiceTest {

    @Mock InventoryMasterItemRepository repository;

    @InjectMocks PlatformInventoryItemService service;

    @Test
    void createItem_blankName_throws() {
        assertThatThrownBy(() -> service.createItem("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void createItem_duplicate_throws() {
        when(repository.existsByNameIgnoreCase("Cotton")).thenReturn(true);

        assertThatThrownBy(() -> service.createItem("Cotton"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createItem_valid_savesTrimmed() {
        when(repository.existsByNameIgnoreCase("Cotton")).thenReturn(false);
        when(repository.save(any(InventoryMasterItem.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryMasterItem result = service.createItem("  Cotton  ");

        assertThat(result.getName()).isEqualTo("Cotton");
    }

    @Test
    void importCsv_addsNewSkipsDuplicatesAndBlanks() throws Exception {
        when(repository.existsByNameIgnoreCase("Cotton")).thenReturn(false);
        when(repository.existsByNameIgnoreCase("Syringe")).thenReturn(true);
        when(repository.save(any(InventoryMasterItem.class))).thenAnswer(inv -> inv.getArgument(0));

        String csv = "name\nCotton\nSyringe\n\n";
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv", csv.getBytes());

        Map<String, Object> result = service.importCsv(file);

        assertThat(result.get("imported")).isEqualTo(1);
        assertThat(result.get("skipped")).isEqualTo(1);
        verify(repository, times(1)).save(any(InventoryMasterItem.class));
    }

    @Test
    void listItems_returnsOrdered() {
        InventoryMasterItem a = new InventoryMasterItem();
        a.setName("Bandage");
        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(a));

        assertThat(service.listItems()).containsExactly(a);
    }
}

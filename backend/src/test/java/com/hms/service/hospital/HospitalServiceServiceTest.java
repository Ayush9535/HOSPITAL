package com.hms.service.hospital;

import com.hms.entity.HospitalServiceEntity;
import com.hms.entity.HospitalServiceItem;
import com.hms.entity.InventoryItem;
import com.hms.repository.HospitalServiceItemRepository;
import com.hms.repository.HospitalServiceRepository;
import com.hms.repository.InventoryItemRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class HospitalServiceServiceTest {

    @Mock HospitalServiceRepository serviceRepository;
    @Mock HospitalServiceItemRepository serviceItemRepository;
    @Mock InventoryItemRepository inventoryItemRepository;
    @Mock SecurityContextHelper securityHelper;

    @InjectMocks HospitalServiceService service;

    @Test
    void createService_blankName_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        assertThatThrownBy(() -> service.createService("  ", new BigDecimal("100"), List.of(2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void createService_noItems_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        assertThatThrownBy(() -> service.createService("Dressing", new BigDecimal("100"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void createService_negativeCharge_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        assertThatThrownBy(() -> service.createService("Dressing", new BigDecimal("-5"), List.of(2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("charge");
    }

    @Test
    void createService_valid_savesServiceAndJoinRows() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(serviceRepository.save(any(HospitalServiceEntity.class))).thenAnswer(inv -> {
            HospitalServiceEntity s = inv.getArgument(0);
            s.setId(9L);
            return s;
        });

        HospitalServiceEntity result = service.createService("Dressing", new BigDecimal("150"), List.of(2L, 3L));

        assertThat(result.getName()).isEqualTo("Dressing");
        assertThat(result.getHospitalId()).isEqualTo(1L);
        assertThat(result.getCharge()).isEqualByComparingTo("150");
        verify(serviceItemRepository, times(2)).save(any(HospitalServiceItem.class));
    }

    @Test
    void listServices_returnsHospitalScoped() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        HospitalServiceEntity s = new HospitalServiceEntity();
        s.setName("Dressing");
        when(serviceRepository.findByHospitalIdAndIsActiveTrueOrderByNameAsc(1L)).thenReturn(List.of(s));

        assertThat(service.listServices()).containsExactly(s);
    }

    @Test
    void getItemNamesForService_resolvesMasterNames() {
        HospitalServiceItem link = new HospitalServiceItem();
        link.setMasterItemId(2L);
        when(serviceItemRepository.findByServiceId(9L)).thenReturn(List.of(link));
        InventoryItem cotton = new InventoryItem();
        cotton.setName("Cotton");
        when(inventoryItemRepository.findById(2L)).thenReturn(Optional.of(cotton));

        assertThat(service.getItemNamesForService(9L)).containsExactly("Cotton");
    }

    @Test
    void updateService_notFound_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(serviceRepository.findByIdAndHospitalId(99L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateService(99L, "X", new BigDecimal("1"), List.of(2L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void updateService_replacesItems() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        HospitalServiceEntity existing = new HospitalServiceEntity();
        existing.setId(5L);
        existing.setHospitalId(1L);
        when(serviceRepository.findByIdAndHospitalId(5L, 1L)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(HospitalServiceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateService(5L, "New", new BigDecimal("200"), List.of(7L));

        verify(serviceItemRepository).deleteByServiceId(5L);
        verify(serviceItemRepository).save(any(HospitalServiceItem.class));
    }

    @Test
    void deleteService_softDeletes() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        HospitalServiceEntity existing = new HospitalServiceEntity();
        existing.setId(5L);
        existing.setHospitalId(1L);
        existing.setIsActive(true);
        when(serviceRepository.findByIdAndHospitalId(5L, 1L)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(HospitalServiceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deleteService(5L);

        assertThat(existing.getIsActive()).isFalse();
    }
}

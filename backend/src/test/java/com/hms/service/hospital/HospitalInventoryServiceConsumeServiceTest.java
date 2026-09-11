package com.hms.service.hospital;

import com.hms.entity.HospitalInventory;
import com.hms.entity.HospitalServiceEntity;
import com.hms.entity.HospitalServiceItem;
import com.hms.entity.InventoryMasterItem;
import com.hms.repository.*;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HospitalInventoryServiceConsumeServiceTest {

    @Mock HospitalInventoryRepository hospitalInventoryRepository;
    @Mock InventoryItemRepository inventoryItemRepository;
    @Mock HospitalInventoryPurchaseRepository hospitalInventoryPurchaseRepository;
    @Mock HospitalServiceRepository hospitalServiceRepository;
    @Mock HospitalServiceItemRepository hospitalServiceItemRepository;
    @Mock InventoryMasterItemRepository inventoryMasterItemRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock HospitalWebSocketHandler webSocketHandler;

    @InjectMocks HospitalInventoryService service;

    private HospitalServiceEntity svc(Long id, String name, String charge) {
        HospitalServiceEntity s = new HospitalServiceEntity();
        s.setId(id); s.setName(name); s.setHospitalId(1L); s.setCharge(new BigDecimal(charge));
        return s;
    }
    private HospitalServiceItem link(Long masterId) {
        HospitalServiceItem l = new HospitalServiceItem();
        l.setMasterItemId(masterId);
        return l;
    }
    private InventoryMasterItem master(Long id, String name) {
        InventoryMasterItem m = new InventoryMasterItem();
        m.setId(id); m.setName(name);
        return m;
    }
    private HospitalInventory stock(Long id, String name, int qty) {
        HospitalInventory s = new HospitalInventory();
        s.setId(id); s.setName(name); s.setHospitalId(1L); s.setStockQuantity(qty); s.setIsActive(true);
        return s;
    }

    @Test
    void consumeService_allItemsInStock_deductsAndReturnsCharge() {
        when(hospitalServiceRepository.findByIdAndHospitalId(9L, 1L)).thenReturn(Optional.of(svc(9L, "Dressing", "150")));
        when(hospitalServiceItemRepository.findByServiceId(9L)).thenReturn(List.of(link(2L), link(3L)));
        when(inventoryMasterItemRepository.findById(2L)).thenReturn(Optional.of(master(2L, "Cotton")));
        when(inventoryMasterItemRepository.findById(3L)).thenReturn(Optional.of(master(3L, "Bandage")));
        HospitalInventory cotton = stock(20L, "Cotton", 50);
        HospitalInventory bandage = stock(21L, "Bandage", 30);
        when(hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue("Cotton", 1L)).thenReturn(new ArrayList<>(List.of(cotton)));
        when(hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue("Bandage", 1L)).thenReturn(new ArrayList<>(List.of(bandage)));

        BigDecimal charge = service.consumeService(9L, 1, 1L);

        assertThat(charge).isEqualByComparingTo("150");
        assertThat(cotton.getStockQuantity()).isEqualTo(49);
        assertThat(bandage.getStockQuantity()).isEqualTo(29);
    }

    @Test
    void consumeService_itemOutOfStock_throwsAndDeductsNothing() {
        when(hospitalServiceRepository.findByIdAndHospitalId(9L, 1L)).thenReturn(Optional.of(svc(9L, "Dressing", "150")));
        when(hospitalServiceItemRepository.findByServiceId(9L)).thenReturn(List.of(link(2L), link(3L)));
        when(inventoryMasterItemRepository.findById(2L)).thenReturn(Optional.of(master(2L, "Cotton")));
        when(inventoryMasterItemRepository.findById(3L)).thenReturn(Optional.of(master(3L, "Bandage")));
        when(hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue("Cotton", 1L)).thenReturn(new ArrayList<>(List.of(stock(20L, "Cotton", 50))));
        when(hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue("Bandage", 1L)).thenReturn(new ArrayList<>()); // no bandage stock

        assertThatThrownBy(() -> service.consumeService(9L, 1, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of stock");
    }

    @Test
    void consumeService_quantityExceedsStock_throws() {
        when(hospitalServiceRepository.findByIdAndHospitalId(9L, 1L)).thenReturn(Optional.of(svc(9L, "Dressing", "150")));
        when(hospitalServiceItemRepository.findByServiceId(9L)).thenReturn(List.of(link(2L)));
        when(inventoryMasterItemRepository.findById(2L)).thenReturn(Optional.of(master(2L, "Cotton")));
        when(hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue("Cotton", 1L)).thenReturn(new ArrayList<>(List.of(stock(20L, "Cotton", 2))));

        assertThatThrownBy(() -> service.consumeService(9L, 3, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of stock");
    }

    @Test
    void consumeService_serviceNotFound_throws() {
        when(hospitalServiceRepository.findByIdAndHospitalId(99L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.consumeService(99L, 1, 1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Service not found");
    }

    @Test
    void getLowStockItems_returnsBelowMinLevel() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        HospitalInventory low = stock(1L, "Cotton", 5); low.setMinStockLevel(10);
        HospitalInventory good = stock(2L, "Gauze", 40); good.setMinStockLevel(10);
        when(hospitalInventoryRepository.findByHospitalId(1L)).thenReturn(List.of(low, good));

        List<HospitalInventory> result = service.getLowStockItems();

        assertThat(result).containsExactly(low);
    }
}

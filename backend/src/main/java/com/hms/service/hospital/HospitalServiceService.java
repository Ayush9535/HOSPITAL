package com.hms.service.hospital;

import com.hms.exception.ResourceNotFoundException;

import com.hms.entity.HospitalServiceEntity;
import com.hms.entity.HospitalServiceItem;
import com.hms.repository.HospitalServiceItemRepository;
import com.hms.repository.HospitalServiceRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class HospitalServiceService {

    @Autowired
    private HospitalServiceRepository serviceRepository;

    @Autowired
    private HospitalServiceItemRepository serviceItemRepository;

    @Autowired
    private com.hms.repository.InventoryItemRepository inventoryItemRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    public List<HospitalServiceEntity> listServices() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return serviceRepository.findByHospitalIdAndIsActiveTrueOrderByNameAsc(hospitalId);
    }

    /**
     * Resolve the linked item names. The ids stored in hospital_service_items come from the
     * catalog the Service Lookup shows, which is `inventory_items` (what the platform writes)
     * — not the legacy `inventory_master_items` table this used to read, which would leave
     * every saved service showing no items.
     */
    public List<String> getItemNamesForService(Long serviceId) {
        List<String> names = new ArrayList<>();
        for (HospitalServiceItem link : serviceItemRepository.findByServiceId(serviceId)) {
            inventoryItemRepository.findById(link.getMasterItemId())
                    .ifPresent(m -> names.add(m.getName()));
        }
        return names;
    }

    public List<Long> getMasterItemIdsForService(Long serviceId) {
        List<Long> ids = new ArrayList<>();
        for (HospitalServiceItem link : serviceItemRepository.findByServiceId(serviceId)) {
            ids.add(link.getMasterItemId());
        }
        return ids;
    }

    @Transactional
    public HospitalServiceEntity createService(String name, BigDecimal charge, List<Long> masterItemIds) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        validate(name, charge, masterItemIds);

        HospitalServiceEntity svc = new HospitalServiceEntity();
        svc.setHospitalId(hospitalId);
        svc.setName(name.trim());
        svc.setCharge(charge);
        svc.setIsActive(true);
        HospitalServiceEntity saved = serviceRepository.save(svc);

        saveItems(saved.getId(), masterItemIds);
        return saved;
    }

    @Transactional
    public HospitalServiceEntity updateService(Long id, String name, BigDecimal charge, List<Long> masterItemIds) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        validate(name, charge, masterItemIds);
        HospitalServiceEntity svc = serviceRepository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found"));
        svc.setName(name.trim());
        svc.setCharge(charge);
        serviceRepository.save(svc);

        serviceItemRepository.deleteByServiceId(id);
        saveItems(id, masterItemIds);
        return svc;
    }

    @Transactional
    public void deleteService(Long id) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        HospitalServiceEntity svc = serviceRepository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found"));
        svc.setIsActive(false);
        serviceRepository.save(svc);
    }

    private void validate(String name, BigDecimal charge, List<Long> masterItemIds) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Service name is required");
        }
        if (charge == null || charge.signum() < 0) {
            throw new IllegalArgumentException("Service charge must be zero or positive");
        }
        if (masterItemIds == null || masterItemIds.isEmpty()) {
            throw new IllegalArgumentException("Service must have at least one relevant item");
        }
    }

    private void saveItems(Long serviceId, List<Long> masterItemIds) {
        for (Long itemId : masterItemIds) {
            HospitalServiceItem link = new HospitalServiceItem();
            link.setServiceId(serviceId);
            link.setMasterItemId(itemId);
            serviceItemRepository.save(link);
        }
    }
}

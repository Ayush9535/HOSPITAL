package com.hms.service.pharmacy;

import com.hms.exception.ResourceNotFoundException;

import com.hms.dto.pharmacy.MedicineMasterRequest;
import com.hms.entity.MedicineList;
import com.hms.entity.pharmacy.MedicineMaster;
import com.hms.repository.MedicineListRepository;
import com.hms.repository.pharmacy.MedicineMasterRepository;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Service
public class MedicineMasterService {

    private static final Logger logger = LoggerFactory.getLogger(MedicineMasterService.class);

    @Autowired
    private MedicineMasterRepository repository;

    @Autowired
    private MedicineListRepository medicineListRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private HospitalWebSocketHandler webSocketHandler;

    @Transactional
    public MedicineMaster create(MedicineMasterRequest req) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        MedicineMaster m = new MedicineMaster();
        m.setHospitalId(hospitalId);
        m.setBranchId(securityHelper.getCurrentBranchId());
        mapDtoToEntity(req, m);
        m = repository.save(m);
        m.setMedicineCode("MED" + (1000 + m.getId()));
        m = repository.save(m);
        try { webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}"); } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after medicine master creation", e);
        }
        return m;
    }

    @Transactional
    public MedicineMaster update(Long id, MedicineMasterRequest req) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        MedicineMaster m = repository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Medicine not found in catalog"));
        mapDtoToEntity(req, m);
        m = repository.save(m);
        try { webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}"); } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after medicine master update", e);
        }
        return m;
    }

    public MedicineMaster getById(Long id) {
        return repository.findByIdAndHospitalId(id, securityHelper.getCurrentHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Medicine not found"));
    }

    public Page<MedicineMaster> searchAndList(String query, Pageable pageable) {
        Long hid = securityHelper.getCurrentHospitalId();
        Long branchId = securityHelper.getCurrentBranchId();
        if (query != null && !query.trim().isEmpty()) {
            return repository.searchMedicines(hid, branchId, query, pageable);
        }
        return repository.findScoped(hid, branchId, pageable);
    }

    public List<MedicineMaster> autocomplete(String query) {
        return repository.findTop10ByHospitalIdAndMedicineNameContainingIgnoreCase(
                securityHelper.getCurrentHospitalId(), securityHelper.getCurrentBranchId(), query);
    }

    /**
     * Search the platform-wide medicine catalog (name + type). Used by the pharmacy
     * purchase form to source medicine names/types now that the pharmacy-local
     * Medicine Master tab is gone; the purchase itself creates the local record.
     */
    public List<MedicineList> searchPlatformCatalog(String query) {
        if (query == null || query.trim().isEmpty()) return java.util.Collections.emptyList();
        return medicineListRepository.findByNameContainingIgnoreCase(query.trim());
    }

    private void mapDtoToEntity(MedicineMasterRequest req, MedicineMaster m) {

        m.setMedicineName(req.getMedicineName());
        m.setGenericName(req.getGenericName());
        m.setCategoryId(req.getCategoryId());
        m.setManufacturerId(req.getManufacturerId());
        m.setMedicineType(req.getMedicineType());
        m.setScheduleType(req.getScheduleType());
        m.setDosageForm(req.getDosageForm());
        m.setStrength(req.getStrength());
        m.setUnitOfMeasure(req.getUnitOfMeasure());
        m.setReorderLevel(req.getReorderLevel() != null ? req.getReorderLevel() : 0);
        m.setMinStockLevel(req.getMinStockLevel() != null ? req.getMinStockLevel() : 0);
        m.setGstPercentage(req.getGstPercentage());
        if (req.getRequiresPrescription() != null) m.setRequiresPrescription(req.getRequiresPrescription());
        if (req.getIsActive() != null) m.setIsActive(req.getIsActive());
    }
    @Transactional
    public MedicineMaster toggleStatus(Long id, Boolean isActive) {
        MedicineMaster m = repository.findByIdAndHospitalId(id, securityHelper.getCurrentHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Medicine not found"));
        m.setIsActive(isActive);
        return repository.save(m);
    }

}

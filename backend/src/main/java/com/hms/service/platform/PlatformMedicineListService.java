package com.hms.service.platform;

import com.hms.exception.ResourceNotFoundException;

import com.hms.entity.MedicineList;
import com.hms.repository.MedicineListRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PlatformMedicineListService {

    @Autowired
    private MedicineListRepository repository;

    /**
     * Get medicines filtered by hospital type (HOSPITAL, CLINIC, or PHARMACY).
     * Supports pagination and search.
     */
    public Page<MedicineList> searchMedicinesByType(String hospitalType, String query, Pageable pageable) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }

        Pageable sortedPageable = PageRequest.of(
            pageable.getPageNumber(),
            pageable.getPageSize(),
            Sort.by("name").ascending()
        );

        if (query == null || query.trim().isEmpty()) {
            return repository.findByHospitalTypeAndNameContainingIgnoreCase(hospitalType, "", sortedPageable);
        }

        return repository.findByHospitalTypeAndNameContainingIgnoreCase(hospitalType, query.trim(), sortedPageable);
    }

    /**
     * Get all medicines for a specific hospital type.
     */
    public List<MedicineList> getAllMedicinesByType(String hospitalType) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }
        return repository.findByHospitalTypeAndNameContainingIgnoreCase(hospitalType, "");
    }

    /**
     * Create a new medicine for a specific hospital type.
     */
    public MedicineList createMedicine(String hospitalType, String name, String type) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Medicine name is required");
        }
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Medicine type is required");
        }

        String trimmedName = name.trim();

        // Check if medicine with this name already exists for this tenant type
        if (repository.existsByHospitalTypeAndNameIgnoreCase(hospitalType, trimmedName)) {
            throw new IllegalArgumentException("Medicine already exists for " + hospitalType + ": " + trimmedName);
        }

        MedicineList medicine = new MedicineList();
        medicine.setName(trimmedName);
        medicine.setType(type.trim());
        medicine.setHospitalType(hospitalType);

        return repository.save(medicine);
    }

    /**
     * Get a specific medicine by ID and hospital type (isolation check).
     */
    public MedicineList getMedicineByIdAndType(Long id, String hospitalType) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }
        return repository.findByHospitalTypeAndNameIgnoreCase(hospitalType, "")
            .stream()
            .filter(m -> m.getId().equals(id))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Medicine not found for " + hospitalType));
    }

    /**
     * Update a medicine (with hospital type isolation).
     */
    public MedicineList updateMedicine(Long id, String hospitalType, String name, String type) {
        MedicineList medicine = getMedicineByIdAndType(id, hospitalType);

        if (name != null && !name.trim().isEmpty()) {
            medicine.setName(name.trim());
        }
        if (type != null && !type.trim().isEmpty()) {
            medicine.setType(type.trim());
        }

        return repository.save(medicine);
    }

    /**
     * Delete a medicine (with hospital type isolation).
     */
    public void deleteMedicine(Long id, String hospitalType) {
        MedicineList medicine = getMedicineByIdAndType(id, hospitalType);
        repository.delete(medicine);
    }
}

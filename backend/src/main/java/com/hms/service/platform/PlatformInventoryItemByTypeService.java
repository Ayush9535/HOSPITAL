package com.hms.service.platform;

import com.hms.exception.ResourceNotFoundException;

import com.hms.entity.InventoryItem;
import com.hms.repository.InventoryItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service for managing InventoryItem by tenant type (HOSPITAL, CLINIC, PHARMACY).
 * Enforces tenant isolation via hospitalType parameter.
 */
@Service
public class PlatformInventoryItemByTypeService {

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    /**
     * Get all inventory items for a specific tenant type.
     */
    public List<InventoryItem> getItemsByType(String hospitalType) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            return inventoryItemRepository.findAll();
        }
        return inventoryItemRepository.findByHospitalType(hospitalType);
    }

    /**
     * Search inventory items by name for a specific tenant type.
     */
    public Page<InventoryItem> searchItemsByType(String hospitalType, String query, Pageable pageable) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            if (query == null || query.isEmpty()) {
                return inventoryItemRepository.findAll(pageable);
            }
            return inventoryItemRepository.findByNameContainingIgnoreCase(query, pageable);
        }

        if (query == null || query.isEmpty()) {
            return inventoryItemRepository.findByHospitalType(hospitalType, pageable);
        }
        return inventoryItemRepository.searchByHospitalTypeAndName(hospitalType, query, pageable);
    }

    /**
     * Get inventory item by ID with isolation check.
     */
    public InventoryItem getItemByIdAndType(Long id, String hospitalType) {
        Optional<InventoryItem> optional;

        if (hospitalType != null && !hospitalType.isEmpty()) {
            optional = inventoryItemRepository.findByHospitalTypeAndId(hospitalType, id);
        } else {
            optional = inventoryItemRepository.findById(id);
        }

        return optional.orElseThrow(() -> new ResourceNotFoundException("Inventory item not found with ID: " + id));
    }

    /**
     * Create an inventory item for a specific tenant type.
     */
    public InventoryItem createItem(String hospitalType, String name, String type) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Item name is required");
        }

        // Check if item with same name already exists for this type
        if (hospitalType != null && !hospitalType.isEmpty()) {
            Optional<InventoryItem> existing = inventoryItemRepository.findByHospitalTypeAndName(hospitalType, name.trim());
            if (existing.isPresent()) {
                throw new IllegalArgumentException("Item already exists: " + name);
            }
        }

        InventoryItem item = new InventoryItem();
        item.setName(name.trim());
        item.setType(type);
        item.setHospitalType(hospitalType);
        item.setIsActive(true);

        return inventoryItemRepository.save(item);
    }

    /**
     * Update an inventory item with isolation check.
     */
    public InventoryItem updateItem(Long id, String hospitalType, String name, String type) {
        InventoryItem item = getItemByIdAndType(id, hospitalType);

        if (name != null && !name.trim().isEmpty()) {
            item.setName(name.trim());
        }
        if (type != null && !type.trim().isEmpty()) {
            item.setType(type);
        }

        return inventoryItemRepository.save(item);
    }

    /**
     * Delete an inventory item with isolation check.
     */
    public void deleteItem(Long id, String hospitalType) {
        InventoryItem item = getItemByIdAndType(id, hospitalType);
        inventoryItemRepository.delete(item);
    }
}

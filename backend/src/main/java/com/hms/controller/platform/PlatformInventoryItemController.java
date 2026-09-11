package com.hms.controller.platform;

import com.hms.entity.InventoryItem;
import com.hms.entity.InventoryMasterItem;
import com.hms.service.platform.PlatformInventoryItemService;
import com.hms.service.platform.PlatformInventoryItemByTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/platform/inventory-master")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformInventoryItemController {

    @Autowired
    private PlatformInventoryItemService service;

    @Autowired
    private PlatformInventoryItemByTypeService byTypeService;

    @GetMapping
    public ResponseEntity<?> getItems(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(required = false) String hospitalType,
            org.springframework.data.domain.Pageable pageable) {
        // If hospitalType is provided, use the tenant-isolated service
        if (hospitalType != null && !hospitalType.isEmpty()) {
            Page<InventoryItem> items = byTypeService.searchItemsByType(hospitalType, search, pageable);
            return ResponseEntity.ok(items);
        }
        // Otherwise, use the global catalog service (backward compatibility)
        return ResponseEntity.ok(service.listItems(search, pageable));
    }

    @PostMapping
    public ResponseEntity<?> createItem(
            @RequestParam(required = false) String hospitalType,
            @RequestBody Map<String, String> body) {
        try {
            // If hospitalType is provided, use tenant-isolated service
            if (hospitalType != null && !hospitalType.isEmpty()) {
                InventoryItem item = byTypeService.createItem(
                    hospitalType,
                    body.get("name"),
                    body.get("type")
                );
                return ResponseEntity.ok(item);
            }
            // Otherwise, use global catalog service (backward compatibility)
            return ResponseEntity.ok(service.createItem(body.get("name")));
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateItem(
            @PathVariable Long id,
            @RequestParam(required = false) String hospitalType,
            @RequestBody Map<String, String> body) {
        try {
            // If hospitalType is provided, use tenant-isolated service
            if (hospitalType != null && !hospitalType.isEmpty()) {
                InventoryItem item = byTypeService.updateItem(
                    id,
                    hospitalType,
                    body.get("name"),
                    body.get("type")
                );
                return ResponseEntity.ok(item);
            }
            // Fallback: direct update without type isolation
            return ResponseEntity.badRequest().body("hospitalType is required for updates");
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteItem(
            @PathVariable Long id,
            @RequestParam(required = false) String hospitalType) {
        try {
            // If hospitalType is provided, use tenant-isolated service
            if (hospitalType != null && !hospitalType.isEmpty()) {
                byTypeService.deleteItem(id, hospitalType);
            } else {
                // Otherwise, use global catalog service (backward compatibility)
                service.deleteItem(id);
            }
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @PostMapping("/import-csv")
    public ResponseEntity<?> importCsv(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(service.importCsv(file));
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }
}

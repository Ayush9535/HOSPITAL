package com.hms.service.platform;

import com.hms.entity.InventoryMasterItem;
import com.hms.repository.InventoryMasterItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlatformInventoryItemService {

    @Autowired
    private InventoryMasterItemRepository repository;

    public List<InventoryMasterItem> listItems() {
        return repository.findAllByOrderByNameAsc();
    }

    public org.springframework.data.domain.Page<InventoryMasterItem> listItems(String search, org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Pageable sortedPageable = org.springframework.data.domain.PageRequest.of(
            pageable.getPageNumber(),
            pageable.getPageSize(),
            org.springframework.data.domain.Sort.by("name").ascending()
        );
        if (search == null || search.trim().isEmpty()) {
            return repository.findAll(sortedPageable);
        }
        return repository.findByNameContainingIgnoreCaseOrderByNameAsc(search.trim(), sortedPageable);
    }

    public InventoryMasterItem createItem(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Item name is required");
        }
        String trimmed = name.trim();
        if (repository.existsByNameIgnoreCase(trimmed)) {
            throw new IllegalArgumentException("Item already exists: " + trimmed);
        }
        InventoryMasterItem item = new InventoryMasterItem();
        item.setName(trimmed);
        return repository.save(item);
    }

    public void deleteItem(Long id) {
        repository.deleteById(id);
    }

    public Map<String, Object> importCsv(MultipartFile file) throws Exception {
        com.hms.util.CsvUploads.validate(file);
        int imported = 0, skipped = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), "UTF-8"))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) { continue; }
                if (lineNum == 1 && trimmed.toLowerCase().startsWith("name")) { continue; }
                String name = trimmed.split(",", -1)[0].trim().replaceAll("(^\")|(\"$)", "");
                if (name.isEmpty()) { continue; }
                if (repository.existsByNameIgnoreCase(name)) { skipped++; continue; }
                InventoryMasterItem item = new InventoryMasterItem();
                item.setName(name);
                repository.save(item);
                imported++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("imported", imported);
        result.put("skipped", skipped);
        return result;
    }
}

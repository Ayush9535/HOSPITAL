package com.hms.service.pharmacy;

import com.hms.dto.pharmacy.CategoryRequest;
import com.hms.entity.pharmacy.MedicineCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MedicineCategoryService {
    MedicineCategory createCategory(CategoryRequest request);
    Page<MedicineCategory> getAllCategories(String search, Pageable pageable);
    MedicineCategory getCategoryById(Long id);
    MedicineCategory updateCategory(Long id, CategoryRequest request);
    MedicineCategory toggleStatus(Long id);
}

package com.hms.service.pharmacy.impl;

import com.hms.exception.ResourceNotFoundException;

import com.hms.dto.pharmacy.CategoryRequest;
import com.hms.entity.pharmacy.MedicineCategory;
import com.hms.repository.pharmacy.MedicineCategoryRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.pharmacy.MedicineCategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MedicineCategoryServiceImpl implements MedicineCategoryService {

    @Autowired
    private MedicineCategoryRepository categoryRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Override
    @Transactional
    public MedicineCategory createCategory(CategoryRequest request) {
        MedicineCategory category = new MedicineCategory();
        category.setHospitalId(securityHelper.getCurrentHospitalId());
        category.setCategoryName(request.getCategoryName());
        category.setDescription(request.getDescription());
        category.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
        return categoryRepository.save(category);
    }

    @Override
    public Page<MedicineCategory> getAllCategories(String search, Pageable pageable) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (search != null && !search.trim().isEmpty()) {
            return categoryRepository.findByHospitalIdAndCategoryNameContainingIgnoreCase(hospitalId, search, pageable);
        }
        return categoryRepository.findByHospitalId(hospitalId, pageable);
    }

    @Override
    public MedicineCategory getCategoryById(Long id) {
        return categoryRepository.findByIdAndHospitalId(id, securityHelper.getCurrentHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Medicine category not found"));
    }

    @Override
    @Transactional
    public MedicineCategory updateCategory(Long id, CategoryRequest request) {
        MedicineCategory category = getCategoryById(id);
        category.setCategoryName(request.getCategoryName());
        category.setDescription(request.getDescription());
        if (request.getIsActive() != null) category.setIsActive(request.getIsActive());
        return categoryRepository.save(category);
    }

    @Override
    @Transactional
    public MedicineCategory toggleStatus(Long id) {
        MedicineCategory category = getCategoryById(id);
        category.setIsActive(!category.getIsActive());
        return categoryRepository.save(category);
    }
}

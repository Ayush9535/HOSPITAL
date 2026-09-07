package com.hms.controller.hospital;

import com.hms.entity.Hospital;
import com.hms.repository.FaqRepository;
import com.hms.repository.HospitalRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped FAQ read for a logged-in admin.
 *
 * The old /api/public/faqs endpoint returns every FAQ regardless of tenant type, so a
 * hospital admin saw the clinic's FAQs. This endpoint resolves the caller's tenant from
 * their own hospital record and returns only that tenant type's FAQs (plus any legacy
 * untyped ones). Aliased so clinic and pharmacy sessions each get their own.
 */
@RestController
@RequestMapping({"/hospital/faqs", "/clinic/faqs", "/pharmacy/faqs"})
public class FaqController {

    @Autowired private FaqRepository faqRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private SecurityContextHelper securityHelper;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getFaqs() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
        // The hospital record is the source of truth for the tenant type.
        String type = hospitalRepository.findById(hospitalId)
                .map(Hospital::getType)
                .map(Enum::name)
                .orElse("HOSPITAL");
        return ResponseEntity.ok(faqRepository.findByHospitalTypeOrHospitalTypeIsNullOrderByIdAsc(type));
    }
}

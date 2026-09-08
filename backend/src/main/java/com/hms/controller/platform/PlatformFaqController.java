package com.hms.controller.platform;

import jakarta.validation.Valid;

import com.hms.entity.Faq;
import com.hms.repository.FaqRepository;
import com.hms.service.platform.PlatformFAQService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/platform/faqs")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformFaqController {

    @Autowired
    private FaqRepository faqRepository;

    @Autowired
    private PlatformFAQService platformFAQService;

    /**
     * Get all FAQs or filter by tenant type (HOSPITAL, CLINIC, PHARMACY).
     */
    @GetMapping
    public ResponseEntity<?> getAllFaqs(
            @RequestParam(required = false) String hospitalType,
            @RequestParam(required = false) String search) {
        try {
            // If hospitalType is provided, use isolated service
            if (hospitalType != null && !hospitalType.isEmpty()) {
                if (search != null && !search.isEmpty()) {
                    List<Faq> faqs = platformFAQService.searchFAQs(hospitalType, search);
                    return ResponseEntity.ok(faqs);
                }
                List<Faq> faqs = platformFAQService.getFAQsByType(hospitalType);
                return ResponseEntity.ok(faqs);
            }

            // Otherwise, get all FAQs (backward compatibility)
            return ResponseEntity.ok(faqRepository.findAll());
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getFaq(
            @PathVariable Long id,
            @RequestParam(required = false) String hospitalType) {
        try {
            if (hospitalType != null && !hospitalType.isEmpty()) {
                Faq faq = platformFAQService.getFAQById(id, hospitalType);
                return ResponseEntity.ok(faq);
            }
            return faqRepository.findById(id)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @PostMapping
    public ResponseEntity<?> addFaq(
            @RequestParam(required = false) String hospitalType,
            @Valid @RequestBody Faq faq) {
        try {
            if (faq.getQuestion() == null || faq.getQuestion().trim().isEmpty() ||
                faq.getAnswer() == null || faq.getAnswer().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Question and answer are required.");
            }

            // If hospitalType is provided, use isolated service
            if (hospitalType != null && !hospitalType.isEmpty()) {
                Faq saved = platformFAQService.createFAQ(hospitalType, faq.getQuestion(), faq.getAnswer());
                return ResponseEntity.ok(saved);
            }

            // Otherwise, save directly (backward compatibility)
            Faq saved = faqRepository.save(faq);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateFaq(
            @PathVariable Long id,
            @RequestParam(required = false) String hospitalType,
            @Valid @RequestBody Faq request) {
        try {
            if (hospitalType != null && !hospitalType.isEmpty()) {
                Faq faq = platformFAQService.updateFAQ(id, hospitalType, request.getQuestion(), request.getAnswer());
                return ResponseEntity.ok(faq);
            }

            // Otherwise, update directly (backward compatibility)
            return faqRepository.findById(id)
                    .map(faq -> {
                        if (request.getQuestion() != null && !request.getQuestion().isEmpty()) {
                            faq.setQuestion(request.getQuestion());
                        }
                        if (request.getAnswer() != null && !request.getAnswer().isEmpty()) {
                            faq.setAnswer(request.getAnswer());
                        }
                        return ResponseEntity.ok(faqRepository.save(faq));
                    })
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFaq(
            @PathVariable Long id,
            @RequestParam(required = false) String hospitalType) {
        try {
            if (hospitalType != null && !hospitalType.isEmpty()) {
                platformFAQService.deleteFAQ(id, hospitalType);
            } else {
                if (!faqRepository.existsById(id)) {
                    return ResponseEntity.notFound().build();
                }
                faqRepository.deleteById(id);
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }
}

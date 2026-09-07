package com.hms.service.platform;

import com.hms.exception.ResourceNotFoundException;

import com.hms.entity.Faq;
import com.hms.repository.FaqRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PlatformFAQService {

    @Autowired private com.hms.service.RealtimeNotifier notifier;

    @Autowired
    private FaqRepository repository;

    /**
     * Get all FAQs filtered by hospital type (HOSPITAL, CLINIC, or PHARMACY).
     */
    public List<Faq> getFAQsByType(String hospitalType) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }
        return repository.findByHospitalTypeOrderByIdAsc(hospitalType);
    }

    /**
     * Get FAQ count by hospital type.
     */
    public long getFAQCountByType(String hospitalType) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }
        return repository.countByHospitalType(hospitalType);
    }

    /**
     * Get a specific FAQ by ID (with isolation check).
     */
    public Faq getFAQById(Long faqId, String hospitalType) {
        Faq faq = repository.findById(faqId)
            .orElseThrow(() -> new ResourceNotFoundException("FAQ not found"));

        // Isolation check
        if (faq.getHospitalType() != null && !faq.getHospitalType().equals(hospitalType)) {
            throw new RuntimeException("Unauthorized: FAQ does not belong to " + hospitalType);
        }

        return faq;
    }

    /**
     * Create a new FAQ for a specific hospital type.
     */
    @Transactional
    public Faq createFAQ(String hospitalType, String question, String answer) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("Question is required");
        }
        if (answer == null || answer.trim().isEmpty()) {
            throw new IllegalArgumentException("Answer is required");
        }

        Faq faq = new Faq();
        faq.setHospitalType(hospitalType);
        faq.setQuestion(question.trim());
        faq.setAnswer(answer.trim());

        Faq saved = repository.save(faq);
        // FAQs are platform content that every tenant reads, so fan out to all connected tenants
        // rather than targeting one -- there is no single owner to notify.
        notifier.refreshAllTenants();
        return saved;
    }

    /**
     * Update a FAQ (with isolation check).
     */
    @Transactional
    public Faq updateFAQ(Long faqId, String hospitalType, String question, String answer) {
        Faq faq = getFAQById(faqId, hospitalType);

        if (question != null && !question.trim().isEmpty()) {
            faq.setQuestion(question.trim());
        }
        if (answer != null && !answer.trim().isEmpty()) {
            faq.setAnswer(answer.trim());
        }

        Faq saved = repository.save(faq);
        notifier.refreshAllTenants();
        return saved;
    }

    /**
     * Delete a FAQ (with isolation check).
     */
    @Transactional
    public void deleteFAQ(Long faqId, String hospitalType) {
        Faq faq = getFAQById(faqId, hospitalType);
        repository.delete(faq);
        notifier.refreshAllTenants();
    }

    /**
     * Search FAQs by question text.
     */
    public List<Faq> searchFAQs(String hospitalType, String searchTerm) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }

        List<Faq> faqs = getFAQsByType(hospitalType);

        if (searchTerm == null || searchTerm.isEmpty()) {
            return faqs;
        }

        String lowerSearch = searchTerm.toLowerCase();
        return faqs.stream()
            .filter(f -> f.getQuestion().toLowerCase().contains(lowerSearch) ||
                        f.getAnswer().toLowerCase().contains(lowerSearch))
            .toList();
    }
}

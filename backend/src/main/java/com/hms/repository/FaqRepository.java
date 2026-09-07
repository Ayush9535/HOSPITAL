package com.hms.repository;

import com.hms.entity.Faq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import java.util.List;

@Repository
public interface FaqRepository extends JpaRepository<Faq, Long> {

    @Cacheable(value = "faqs")
    List<Faq> findAll();

    @Override
    @CacheEvict(value = "faqs", allEntries = true)
    <S extends Faq> S save(S entity);

    @Override
    @CacheEvict(value = "faqs", allEntries = true)
    void deleteById(Long id);

    // Platform admin: Tenant-type isolated FAQs
    List<Faq> findByHospitalType(String hospitalType);

    List<Faq> findByHospitalTypeOrderByIdAsc(String hospitalType);

    long countByHospitalType(String hospitalType);

    /**
     * Tenant-scoped FAQ read: the FAQs for this tenant type, plus any legacy FAQs that
     * predate the hospital_type column (null type = global, visible to all). New FAQs are
     * always typed, so this isolates them by tenant while not hiding old global ones.
     */
    List<Faq> findByHospitalTypeOrHospitalTypeIsNullOrderByIdAsc(String hospitalType);
}

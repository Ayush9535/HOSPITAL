package com.hms.integration;

import com.hms.entity.Patient;
import com.hms.repository.PatientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test on a REAL MySQL instance (Testcontainers): proves the patient persistence
 * and tenant-scoped repository queries behave correctly against MySQL, not just H2.
 *
 * Business-critical: patient records must never leak across hospitals at the data layer.
 */
class PatientPersistenceIT extends AbstractMySqlIT {

    @Autowired
    private PatientRepository patientRepository;

    private Patient patient(long hospitalId, String name, String publicId) {
        Patient p = new Patient();
        p.setHospitalId(hospitalId);
        p.setPublicId(publicId);
        p.setName(name);
        p.setGender("MALE");
        p.setPhone("9900000001");
        p.setDateOfBirth(LocalDate.of(1990, 1, 1));
        p.setIsActive(true);
        return p;
    }

    @Test
    void persistsAndRetrievesPatientOnRealMySql() {
        Patient saved = patientRepository.save(patient(1L, "Alice", "pub-alice"));

        assertThat(saved.getId()).isNotNull();
        assertThat(patientRepository.findByPublicIdAndHospitalIdAndIsActiveTrue("pub-alice", 1L))
                .isPresent()
                .get()
                .extracting(Patient::getName)
                .isEqualTo("Alice");
    }

    @Test
    void tenantScopedFinderIsolatesAcrossHospitals() {
        patientRepository.save(patient(1L, "Alice", "pub-a"));
        patientRepository.save(patient(2L, "Bob", "pub-b"));

        // Hospital 1's patient is not reachable via hospital 2's id, and vice versa.
        assertThat(patientRepository.findByPublicIdAndHospitalIdAndIsActiveTrue("pub-a", 2L)).isEmpty();
        assertThat(patientRepository.findByPublicIdAndHospitalIdAndIsActiveTrue("pub-b", 1L)).isEmpty();
        assertThat(patientRepository.findByPublicIdAndHospitalIdAndIsActiveTrue("pub-a", 1L)).isPresent();
    }
}

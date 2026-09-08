package com.hms.repository;

import com.hms.entity.IpdAdmission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1.5 — findMaxIpdSequence() must execute on BOTH MySQL (production) and H2
 * (the `test` profile). The original CAST(... AS UNSIGNED) is MySQL-only and made
 * every admitFromOpd() call fail under test, which is why the admission workflow had
 * no automated coverage at all.
 */
@SpringBootTest
@ActiveProfiles("test")
class IpdSequenceQueryTest {

    @Autowired IpdAdmissionRepository repo;

    private void seed(String ipdNumber) {
        IpdAdmission a = new IpdAdmission();
        a.setIpdNumber(ipdNumber);
        a.setPatientId(1L); a.setDoctorId(1L); a.setHospitalId(1L);
        a.setAdmissionType("ELECTIVE"); a.setStatus("ADMITTED");
        a.setAdmissionDatetime(LocalDateTime.now());
        a.setWardId(1L); a.setBedId(1L); a.setAdmissionConfirmed(false);
        repo.save(a);
    }

    @Test
    void executesAndReturnsCorrectMaxAcrossSuffixes() {
        repo.deleteAll();

        // no rows at all -> COALESCE floor
        assertThat(repo.findMaxIpdSequence()).as("empty table").isEqualTo(0L);

        seed("IPD-7");
        assertThat(repo.findMaxIpdSequence()).as("single row").isEqualTo(7L);

        seed("IPD-42");
        seed("IPD-13");
        assertThat(repo.findMaxIpdSequence()).as("max across multiple").isEqualTo(42L);

        // numeric ordering, not lexicographic: "9" must not beat "42"
        repo.deleteAll();
        seed("IPD-9"); seed("IPD-42");
        assertThat(repo.findMaxIpdSequence()).as("numeric not lexicographic").isEqualTo(42L);

        // rows that do not match the IPD- prefix are excluded by the WHERE clause
        repo.deleteAll();
        seed("IPD-5"); seed("LEGACY-999");
        assertThat(repo.findMaxIpdSequence()).as("non-IPD prefixes ignored").isEqualTo(5L);

        repo.deleteAll();
    }
}

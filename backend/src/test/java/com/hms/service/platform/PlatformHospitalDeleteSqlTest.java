package com.hms.service.platform;

import com.hms.dto.CreateHospitalRequest;
import com.hms.entity.Hospital;
import com.hms.entity.HospitalType;
import com.hms.entity.Plan;
import com.hms.repository.HospitalRepository;
import com.hms.repository.PlanRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * SA-1 — the statements deleteHospital actually issues.
 *
 * <p>The original bug was invisible to review because the SQL is assembled as strings: two
 * statements filtered on a hospital_id column that opd and medicine_list do not have. Rather than
 * re-listing the expected columns here (a copy that would drift), these tests capture the SQL the
 * service really runs and hand each statement to the database to prepare. A column or table that
 * does not exist fails to prepare, so the schema itself is the assertion.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlatformHospitalDeleteSqlTest {

    @Autowired PlatformHospitalService service;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PlanRepository planRepository;
    @Autowired DataSource dataSource;

    @SpyBean JdbcTemplate jdbcTemplate;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Hospital newFacility() {
        Plan p = new Plan();
        p.setPublicId(UUID.randomUUID().toString());
        p.setName("SA1SQL-" + uniq());
        p.setType(HospitalType.HOSPITAL);
        p.setMonthlyPrice(BigDecimal.ONE);
        p.setYearlyPrice(BigDecimal.TEN);
        p.setModules(List.of("OPD"));
        p.setIsActive(true);
        planRepository.save(p);

        CreateHospitalRequest req = new CreateHospitalRequest();
        req.setHospitalName("SA1SQL " + uniq());
        req.setAdminEmail("sa1sql-" + uniq() + "@example.test");
        req.setAdminPassword("Passw0rd!23");
        req.setAdminName("Sa Sql");
        req.setType("HOSPITAL");
        req.setPlanPublicId(p.getPublicId());
        req.setBillingPeriod("MONTHLY");
        req.setIsSingleDoctor(false);
        return service.createHospital(req);
    }

    private List<String> captureDeleteSql(Hospital facility) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        service.deleteHospital(facility.getPublicId());
        verify(jdbcTemplate, atLeastOnce()).update(sql.capture(), any(Object[].class));
        List<String> statements = new ArrayList<>();
        for (String s : sql.getAllValues()) {
            if (s.trim().toUpperCase().startsWith("DELETE")) statements.add(s);
        }
        return statements;
    }

    /** Every captured statement must prepare against the live schema. */
    @Test
    void everyColumnReferencedByTheDeleteExistsInTheSchema() throws Exception {
        List<String> statements = captureDeleteSql(newFacility());
        assertThat(statements).as("delete issues statements").isNotEmpty();

        List<String> unpreparable = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            for (String sql : statements) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    assertThat(ps).isNotNull();
                } catch (Exception e) {
                    unpreparable.add(sql + "  ->  " + e.getMessage());
                }
            }
        }
        assertThat(unpreparable)
                .as("statements referencing a table/column the schema does not have")
                .isEmpty();
    }

    /**
     * Ordering, not suppression. Disabling FOREIGN_KEY_CHECKS also disables the ON DELETE CASCADE
     * rules the tenant tables declare against hospitals(id), which is how orphans accumulated.
     */
    @Test
    void neverDisablesForeignKeyChecks() {
        Hospital h = newFacility();
        ArgumentCaptor<String> executed = ArgumentCaptor.forClass(String.class);

        service.deleteHospital(h.getPublicId());

        verify(jdbcTemplate, atLeastOnce()).update(anyString(), any(Object[].class));
        List<String> all = new ArrayList<>();
        try {
            verify(jdbcTemplate, atLeastOnce()).execute(executed.capture());
            all.addAll(executed.getAllValues());
        } catch (AssertionError noExecuteCalls) {
            // execute() is no longer used by the delete path at all — that is the desired state.
        }
        assertThat(all).noneMatch(s -> s.toUpperCase().contains("FOREIGN_KEY_CHECKS"));
    }

    /** The global catalogue must never be a delete target. */
    @Test
    void neverDeletesFromTheGlobalMedicineCatalogue() {
        assertThat(captureDeleteSql(newFacility()))
                .noneMatch(s -> s.toUpperCase().contains("DELETE FROM MEDICINE_LIST"));
    }

    /**
     * All-or-nothing. If any statement fails, the whole delete rolls back and the facility is
     * left exactly as it was — never half-deleted.
     */
    @Test
    void aFailedStepLeavesNoPartialTenantState() {
        Hospital h = newFacility();
        Long id = h.getId();
        long usersBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE hospital_id = ?", Long.class, id);
        assertThat(usersBefore).isPositive();

        // Fail on the very last statement, once every earlier delete has already run.
        doThrow(new org.springframework.dao.DataIntegrityViolationException("boom"))
                .when(jdbcTemplate).update(contains("DELETE FROM hospitals"), any(Object[].class));

        assertThatThrownBy(() -> service.deleteHospital(h.getPublicId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(hospitalRepository.findByPublicId(h.getPublicId()))
                .as("facility survives a failed delete").isPresent();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE hospital_id = ?", Long.class, id))
                .as("admin user rolled back, not left half-deleted").isEqualTo(usersBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hospital_settings WHERE hospital_id = ?", Long.class, id))
                .as("settings rolled back").isPositive();
    }
}

package com.hms.service.platform;

import com.hms.dto.CreateHospitalRequest;
import com.hms.entity.Hospital;
import com.hms.entity.HospitalType;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.entity.Plan;
import com.hms.entity.QueueEntry;
import com.hms.repository.HospitalRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.PlanRepository;
import com.hms.repository.QueueEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SA-1 — deleting a facility.
 *
 * <p>Deletion used to fail outright: it filtered opd and medicine_list on a hospital_id column
 * that neither table has, so the very first OPD statement raised a bad-grammar error and every
 * delete returned 500. These tests run the real statements against the real schema, so a column
 * that does not exist can no longer pass review — the SQL is executed, not inspected.
 *
 * <p>Deliberately not annotated {@code @Transactional}: the property under test is that the
 * service's OWN transaction commits as a whole or rolls back as a whole, which a surrounding
 * test-managed transaction would hide.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlatformHospitalDeleteTest {

    @Autowired PlatformHospitalService service;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PlanRepository planRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired OpdRepository opdRepository;
    @Autowired QueueEntryRepository queueEntryRepository;
    @Autowired JdbcTemplate jdbc;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Plan newPlan(HospitalType type) {
        Plan p = new Plan();
        p.setPublicId(UUID.randomUUID().toString());
        p.setName("SA1-" + uniq());
        p.setType(type);
        p.setMonthlyPrice(BigDecimal.ONE);
        p.setYearlyPrice(BigDecimal.TEN);
        p.setModules(List.of("OPD", "BILLING"));
        p.setIsActive(true);
        return planRepository.save(p);
    }

    /** A whole facility: hospital row, settings, admin user, type-specific admin, subscription, modules. */
    private Hospital newFacility(HospitalType type) {
        Plan plan = newPlan(type);
        CreateHospitalRequest req = new CreateHospitalRequest();
        req.setHospitalName("SA1 " + type + " " + uniq());
        req.setAdminEmail("sa1-" + uniq() + "@example.test");
        req.setAdminPassword("Passw0rd!23");
        req.setAdminName("Sa One");
        req.setType(type.name());
        req.setPlanPublicId(plan.getPublicId());
        req.setBillingPeriod("MONTHLY");
        req.setIsSingleDoctor(false);
        return service.createHospital(req);
    }

    /** An OPD visit plus its queue entry — the rows whose tenancy the old SQL got wrong. */
    private void addOpdVisit(Long hospitalId) {
        Patient patient = new Patient();
        patient.setHospitalId(hospitalId);
        patient.setName("SA1 Patient");
        patient.setPhone(String.format("9%09d", System.nanoTime() % 1_000_000_000L));
        patient.setGender("MALE");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setIsActive(true);
        patient = patientRepository.save(patient);

        Opd opd = new Opd();
        opd.setPatient(patient);
        opd.setStatus(Opd.Status.QUEUED);
        opd = opdRepository.save(opd);
        opd.setCaseId("SA1-" + uniq());
        opd = opdRepository.save(opd);

        QueueEntry entry = new QueueEntry();
        entry.setOpd(opd);
        queueEntryRepository.save(entry);
    }

    private long opdCountFor(Long hospitalId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM opd WHERE patient_id IN (SELECT id FROM patients WHERE hospital_id = ?)",
                Long.class, hospitalId);
    }

    private long countIn(String table, Long hospitalId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE hospital_id = ?",
                Long.class, hospitalId);
    }

    private void assertFacilityFullyRemoved(Hospital facility) {
        Long id = facility.getId();
        assertThat(hospitalRepository.findByPublicId(facility.getPublicId())).isEmpty();
        assertThat(opdCountFor(id)).as("opd visits").isZero();
        assertThat(countIn("patients", id)).as("patients").isZero();
        assertThat(countIn("users", id)).as("users").isZero();
        assertThat(countIn("hospital_settings", id)).as("settings").isZero();
        assertThat(countIn("hospital_plan_subscriptions", id)).as("subscriptions").isZero();
        assertThat(countIn("hospital_modules", id)).as("modules").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hospitals WHERE id = ?", Long.class, id)).isZero();
    }

    @Test
    void deletesAHospital() {
        Hospital h = newFacility(HospitalType.HOSPITAL);
        addOpdVisit(h.getId());
        assertThat(opdCountFor(h.getId())).isEqualTo(1);

        service.deleteHospital(h.getPublicId());

        assertFacilityFullyRemoved(h);
    }

    @Test
    void deletesAClinic() {
        Hospital c = newFacility(HospitalType.CLINIC);
        addOpdVisit(c.getId());

        service.deleteHospital(c.getPublicId());

        assertFacilityFullyRemoved(c);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clinic_admins WHERE hospital_id = ?",
                Long.class, c.getId())).isZero();
    }

    @Test
    void deletesAPharmacy() {
        Hospital p = newFacility(HospitalType.PHARMACY);

        service.deleteHospital(p.getPublicId());

        assertFacilityFullyRemoved(p);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pharmacy_admins WHERE hospital_id = ?",
                Long.class, p.getId())).isZero();
    }

    /** One facility's deletion must not reach into another's rows. */
    @Test
    void leavesOtherFacilitiesUntouched() {
        Hospital doomed = newFacility(HospitalType.HOSPITAL);
        Hospital keeper = newFacility(HospitalType.HOSPITAL);
        addOpdVisit(doomed.getId());
        addOpdVisit(keeper.getId());

        service.deleteHospital(doomed.getPublicId());

        assertThat(hospitalRepository.findByPublicId(keeper.getPublicId())).isPresent();
        assertThat(opdCountFor(keeper.getId())).as("keeper's OPD visit").isEqualTo(1);
        assertThat(countIn("users", keeper.getId())).as("keeper's admin").isEqualTo(1);
    }

    /**
     * medicine_list is the platform-wide catalogue keyed by hospital_type, not a facility's data.
     * The old statement tried to delete from it by hospital_id; had that column still existed the
     * delete would have destroyed the shared catalogue rather than erroring.
     */
    @Test
    void doesNotTouchTheGlobalMedicineCatalogue() {
        Hospital h = newFacility(HospitalType.HOSPITAL);
        jdbc.update("INSERT INTO medicine_list (name, type, hospital_type) VALUES (?, ?, ?)",
                "SA1-Catalogue-" + uniq(), "Tablet", "HOSPITAL");
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM medicine_list", Long.class);

        service.deleteHospital(h.getPublicId());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM medicine_list", Long.class))
                .as("global catalogue is untouched by a tenant delete")
                .isEqualTo(before);
    }

    /** A missing facility is a 404, not a partial delete. */
    @Test
    void unknownFacilityIsRejected() {
        assertThatThrownBy(() -> service.deleteHospital("no-such-public-id"))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
    }
}

package com.hms.service.hospital;

import com.hms.dto.DashboardOverviewDTO;
import com.hms.entity.*;
import com.hms.entity.pharmacy.PharmacySale;
import com.hms.repository.*;
import com.hms.repository.pharmacy.PharmacySaleRepository;
import com.hms.security.SecurityContextHelper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Overview's two hard rules, exercised against a real schema.
 *
 * <p>One: a capability the tenant does not hold produces no block at all. A zero would be a lie of
 * a specific kind — it says "you have a pharmacy and it sold nothing", which is a different fact
 * from "you have no pharmacy", and an admin acts differently on each.
 *
 * <p>Two: an OPD belongs to the hospital of its patient and to no one else. The opd table has no
 * hospital_id, so every aggregate reaches tenancy through the patient, and the doctor — who is
 * joined only to name a speciality — must never be able to widen or narrow what is counted.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(HospitalDashboardService.class)
class HospitalDashboardServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    private static final String UNASSIGNED = HospitalDashboardService.UNASSIGNED;

    /** Hospital ids are IDENTITY-generated, so the fixtures hand them back rather than assume. */
    private long mine;
    private long theirs;

    @Autowired HospitalDashboardService dashboard;
    @Autowired EntityManager em;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired OpdRepository opdRepository;
    // Spied, not mocked: it still reads the real database, but the service's calls to it are
    // recorded so a test can prove the OPD gate stopped a query rather than merely hid its result.
    @SpyBean MedicalRecordRepository medicalRecordRepository;
    @Autowired IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired BedRepository bedRepository;
    @Autowired BillingPaymentRepository billingPaymentRepository;
    @Autowired PharmacySaleRepository pharmacySaleRepository;

    @MockBean BusinessClock businessClock;
    @MockBean SecurityContextHelper securityHelper;

    @BeforeEach
    void setUp() {
        when(businessClock.today()).thenReturn(DAY);
        when(businessClock.now()).thenReturn(DAY.atTime(14, 30));
        when(businessClock.zoneId()).thenReturn(java.time.ZoneId.of("Asia/Kolkata"));
    }

    // ── entitlements ─────────────────────────────────────────────────────────

    @Test
    void aHospitalWithoutAModuleGetsNoBlockForIt() {
        mine = myHospital("OPD");

        DashboardOverviewDTO result = dashboard.getOverview(DashboardRange.TODAY);

        assertThat(result.getCore()).isNotNull();
        assertThat(result.getOpd()).isNotNull();
        assertThat(result.getIpd()).as("no IPD module").isNull();
        assertThat(result.getBeds()).as("BEDS is implied by IPD, which is absent").isNull();
        assertThat(result.getBilling()).isNull();
        assertThat(result.getPharmacy()).isNull();
    }

    @Test
    void withoutOpdTheConsultationQueryIsNeverEvenRun() {
        // The rule has two halves. Hiding the block is the visible half; not asking the database
        // is the other, and only the second one is a gate. Consultations sat on the core block
        // until this review, where they ran for every hospital including those without OPD.
        mine = myHospital("IPD");

        DashboardOverviewDTO result = dashboard.getOverview(DashboardRange.TODAY);

        assertThat(result.getOpd()).as("no OPD capability, no OPD block").isNull();
        assertThat(result.getCore()).isNotNull();
        verify(medicalRecordRepository, never())
                .countOpdConsultationsInRange(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void withOpdButNoActivityConsultationsAreAHonestZero() {
        mine = myHospital("OPD");

        DashboardOverviewDTO.OpdBlock opd = dashboard.getOverview(DashboardRange.TODAY).getOpd();

        assertThat(opd).isNotNull();
        assertThat(opd.consultations()).isZero();
    }

    @Test
    void anOwnedModuleWithNoActivityStillGetsItsBlockWithZeros() {
        mine = myHospital("OPD", "IPD", "BILLING", "PHARMACY");

        DashboardOverviewDTO result = dashboard.getOverview(DashboardRange.TODAY);

        assertThat(result.getOpd().consultations()).isZero();
        assertThat(result.getOpd().count()).isZero();
        assertThat(result.getOpd().trend()).hasSize(1).allSatisfy(b -> assertThat(b.count()).isZero());
        assertThat(result.getOpd().visitTypes()).isEmpty();
        assertThat(result.getOpd().busiestSpecialities()).isEmpty();
        assertThat(result.getIpd().admissions()).isZero();
        assertThat(result.getBilling().collection()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getPharmacy().pharmacySales()).isZero();
    }

    @Test
    void ipdImpliesBedsEvenThoughBedsIsNeverSoldOrPersisted() {
        mine = myHospital("IPD");

        DashboardOverviewDTO result = dashboard.getOverview(DashboardRange.TODAY);

        assertThat(result.getIpd()).isNotNull();
        assertThat(result.getBeds()).as("EntitlementRegistry.resolve expands IPD to BEDS").isNotNull();
    }

    @Test
    void theLiveHospitalRowDecidesNotWhateverTheTokenRemembers() {
        mine = myHospital("OPD");
        assertThat(dashboard.getOverview(DashboardRange.TODAY).getPharmacy()).isNull();

        Hospital h = hospitalRepository.findById(mine).orElseThrow();
        h.getModules().add("PHARMACY");
        hospitalRepository.saveAndFlush(h);
        em.clear();

        assertThat(dashboard.getOverview(DashboardRange.TODAY).getPharmacy())
                .as("plan change takes effect on the next request, not the next login")
                .isNotNull();
    }

    // ── tenancy ──────────────────────────────────────────────────────────────

    @Test
    void anotherHospitalsActivityIsNeverCounted() {
        mine = myHospital("OPD", "IPD", "BILLING", "PHARMACY");
        theirs = otherHospital("OPD", "IPD", "BILLING", "PHARMACY");

        Patient theirPatient = patient(theirs);
        opd(theirPatient, null, DAY.atTime(10, 0), Opd.VisitType.NEW);
        medicalRecord(theirs, DAY.atTime(10, 0), "OPD");
        ipdAdmission(theirs, DAY.atTime(10, 0));
        payment(theirs, "500.00", DAY.atTime(10, 0));
        pharmacySale(theirs, DAY.atTime(10, 0), "POSTED");
        bed(theirs, BedStatus.OCCUPIED);
        em.flush();
        em.clear();

        DashboardOverviewDTO result = dashboard.getOverview(DashboardRange.TODAY);

        assertThat(result.getCore().totalRegisteredPatients()).isZero();
        assertThat(result.getOpd().consultations()).isZero();
        assertThat(result.getOpd().count()).isZero();
        assertThat(result.getIpd().admissions()).isZero();
        assertThat(result.getBilling().collection()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getPharmacy().pharmacySales()).isZero();
        assertThat(result.getBeds().occupied()).isZero();
    }

    @Test
    void anOpdIsOwnedByItsPatientsHospitalNotItsDoctors() {
        mine = myHospital("OPD");
        theirs = otherHospital("OPD");
        Patient myPatient = patient(mine);
        Doctor foreign = doctor(theirs, "Cardiology");
        opd(myPatient, foreign, DAY.atTime(11, 0), Opd.VisitType.NEW);
        em.flush();
        em.clear();

        DashboardOverviewDTO.OpdBlock opd = dashboard.getOverview(DashboardRange.TODAY).getOpd();

        assertThat(opd.count()).as("the visit still belongs to this hospital").isEqualTo(1);
        assertThat(opd.busiestSpecialities())
                .as("but the other tenant's speciality must not leak")
                .containsExactly(new DashboardOverviewDTO.SpecialityCount("Unassigned", 1));
    }

    @Test
    void specialityFallsBackToUnassignedForMissingDoctorAndBlankSpeciality() {
        mine = myHospital("OPD");
        Patient p = patient(mine);
        opd(p, null, DAY.atTime(9, 0), Opd.VisitType.NEW);
        opd(p, null, DAY.atTime(9, 15), Opd.VisitType.NEW);
        // Bean Validation forbids persisting a blank specialization, but the column is only
        // NOT NULL, so legacy/imported rows can still hold one. Reproduce that state directly.
        Doctor blank = doctor(mine, "Orthopedics");
        opd(p, blank, DAY.atTime(9, 30), Opd.VisitType.NEW);
        opd(p, doctor(mine, "Orthopedics"), DAY.atTime(10, 0), Opd.VisitType.NEW);
        em.flush();
        blankSpeciality(blank);
        em.clear();

        DashboardOverviewDTO.OpdBlock opd = dashboard.getOverview(DashboardRange.TODAY).getOpd();

        assertThat(opd.busiestSpecialities()).containsExactly(
                new DashboardOverviewDTO.SpecialityCount("Unassigned", 3),
                new DashboardOverviewDTO.SpecialityCount("Orthopedics", 1));
        assertThat(opd.busiestSpecialities().stream().mapToLong(DashboardOverviewDTO.SpecialityCount::count).sum())
                .as("buckets always account for every visit").isEqualTo(opd.count());
    }

    /**
     * A doctor really can have "Unassigned" typed into the speciality field, and it must not
     * become a second bucket wearing the same name. Merging in SQL is what makes that impossible:
     * merging in Java after the LIMIT would rank two half-sized groups and could drop one of them
     * off the end of a five-row list that should have had one full-sized entry.
     */
    @Test
    void aLiteralUnassignedSpecialityJoinsTheFallbackBucketRatherThanShadowingIt() {
        mine = myHospital("OPD");
        theirs = otherHospital("OPD");
        Patient p = patient(mine);
        Doctor literal = doctor(mine, "Unassigned");
        opd(p, literal, DAY.atTime(9, 0), Opd.VisitType.NEW);
        opd(p, doctor(mine, "unassigned"), DAY.atTime(9, 5), Opd.VisitType.NEW);
        opd(p, null, DAY.atTime(9, 10), Opd.VisitType.NEW);                      // no doctor
        opd(p, doctor(theirs, "Cardiology"), DAY.atTime(9, 15), Opd.VisitType.NEW); // other tenant
        Doctor blank = doctor(mine, "Orthopedics");
        opd(p, blank, DAY.atTime(9, 20), Opd.VisitType.NEW);                     // blanked below
        opd(p, doctor(mine, "Orthopedics"), DAY.atTime(9, 25), Opd.VisitType.NEW);
        em.flush();
        blankSpeciality(blank);
        em.clear();

        List<DashboardOverviewDTO.SpecialityCount> buckets =
                dashboard.getOverview(DashboardRange.TODAY).getOpd().busiestSpecialities();

        assertThat(buckets).filteredOn(b -> b.speciality().equals(UNASSIGNED))
                .as("exactly one bucket may carry this label").hasSize(1);
        assertThat(buckets).containsExactly(
                new DashboardOverviewDTO.SpecialityCount(UNASSIGNED, 5),
                new DashboardOverviewDTO.SpecialityCount("Orthopedics", 1));
    }

    /**
     * Six distinct groups, one of which only wins its place because its fragments were merged
     * before the ranking. Canonicalise after the LIMIT instead and Unassigned arrives as five
     * groups of one, none of which outranks anything, and the real fifth speciality is displaced.
     */
    @Test
    void bucketsAreCanonicalisedBeforeTheTopFiveIsChosenNotAfter() {
        mine = myHospital("OPD");
        theirs = otherHospital("OPD");
        Patient p = patient(mine);
        int minute = 0;
        for (String s : List.of("Cardiology", "Cardiology", "Cardiology", "Cardiology",
                "Orthopedics", "Orthopedics", "Orthopedics",
                "Neurology", "Neurology", "Dermatology")) {
            opd(p, doctor(mine, s), DAY.atTime(9, minute++), Opd.VisitType.NEW);
        }
        // Five one-visit fragments that are all the same bucket once normalised.
        opd(p, doctor(mine, "Unassigned"), DAY.atTime(10, 0), Opd.VisitType.NEW);
        opd(p, doctor(mine, "UNASSIGNED"), DAY.atTime(10, 1), Opd.VisitType.NEW);
        opd(p, null, DAY.atTime(10, 2), Opd.VisitType.NEW);
        opd(p, doctor(theirs, "Radiology"), DAY.atTime(10, 3), Opd.VisitType.NEW);
        Doctor blank = doctor(mine, "Pathology");
        opd(p, blank, DAY.atTime(10, 4), Opd.VisitType.NEW);
        // One more real speciality that must be pushed out of the top five by the merged bucket.
        opd(p, doctor(mine, "Psychiatry"), DAY.atTime(11, 0), Opd.VisitType.NEW);
        em.flush();
        blankSpeciality(blank);
        em.clear();

        List<DashboardOverviewDTO.SpecialityCount> top =
                dashboard.getOverview(DashboardRange.TODAY).getOpd().busiestSpecialities();

        assertThat(top).containsExactly(
                new DashboardOverviewDTO.SpecialityCount(UNASSIGNED, 5),
                new DashboardOverviewDTO.SpecialityCount("Cardiology", 4),
                new DashboardOverviewDTO.SpecialityCount("Orthopedics", 3),
                new DashboardOverviewDTO.SpecialityCount("Neurology", 2),
                new DashboardOverviewDTO.SpecialityCount("Dermatology", 1));
        assertThat(top).extracting(DashboardOverviewDTO.SpecialityCount::speciality)
                .as("Psychiatry and Pathology lose the tie for the last place, deterministically")
                .doesNotContain("Psychiatry", "Pathology");
    }

    /**
     * Count alone is not a total order. Two specialities tied on the last visible row would swap
     * places between one page load and the next, and a chart that reshuffles while nothing changed
     * is a chart nobody trusts.
     */
    @Test
    void tiedSpecialitiesComeBackInTheSameOrderEveryTime() {
        mine = myHospital("OPD");
        Patient p = patient(mine);
        opd(p, doctor(mine, "Radiology"), DAY.atTime(9, 0), Opd.VisitType.NEW);
        opd(p, doctor(mine, "Cardiology"), DAY.atTime(9, 30), Opd.VisitType.NEW);
        opd(p, doctor(mine, "Dermatology"), DAY.atTime(10, 0), Opd.VisitType.NEW);
        em.flush();
        em.clear();

        List<DashboardOverviewDTO.SpecialityCount> first =
                dashboard.getOverview(DashboardRange.TODAY).getOpd().busiestSpecialities();
        List<DashboardOverviewDTO.SpecialityCount> again =
                dashboard.getOverview(DashboardRange.TODAY).getOpd().busiestSpecialities();

        assertThat(first).containsExactlyElementsOf(again);
        assertThat(first).extracting(DashboardOverviewDTO.SpecialityCount::speciality)
                .as("all tied on one visit, so the bucket name is the tie-break")
                .containsExactly("Cardiology", "Dermatology", "Radiology");
    }

    // ── time ─────────────────────────────────────────────────────────────────

    @Test
    void todayIsTheWholeIstDayAndNothingEitherSideOfIt() {
        mine = myHospital("OPD");
        Patient p = patient(mine);
        opd(p, null, DAY.minusDays(1).atTime(23, 59, 59), Opd.VisitType.NEW);
        opd(p, null, DAY.atStartOfDay(), Opd.VisitType.NEW);
        opd(p, null, DAY.atTime(18, 30), Opd.VisitType.NEW);
        opd(p, null, DAY.atTime(23, 59, 59, 999_999_000), Opd.VisitType.NEW);
        opd(p, null, DAY.plusDays(1).atStartOfDay(), Opd.VisitType.NEW);
        em.flush();
        em.clear();

        assertThat(dashboard.getOverview(DashboardRange.TODAY).getOpd().count()).isEqualTo(3);
    }

    @Test
    void trendsCarryEveryDayOfTheRangeIncludingTheSilentOnes() {
        mine = myHospital("OPD", "IPD");
        Patient p = patient(mine);
        opd(p, null, DAY.atTime(10, 0), Opd.VisitType.NEW);
        opd(p, null, DAY.minusDays(3).atTime(10, 0), Opd.VisitType.FOLLOWUP);
        ipdAdmission(mine, DAY.minusDays(2).atTime(10, 0));
        em.flush();
        em.clear();

        DashboardOverviewDTO week = dashboard.getOverview(DashboardRange.LAST_7_DAYS);

        assertThat(week.getFrom()).isEqualTo(DAY.minusDays(6).atStartOfDay());
        assertThat(week.getToExclusive()).isEqualTo(DAY.plusDays(1).atStartOfDay());
        assertThat(week.getOpd().trend()).hasSize(7);
        assertThat(week.getOpd().trend().get(0).date()).isEqualTo(DAY.minusDays(6));
        assertThat(week.getOpd().trend().get(6).date()).isEqualTo(DAY);
        assertThat(week.getOpd().trend().get(6).count()).isEqualTo(1);
        assertThat(week.getOpd().trend().get(3).count()).isEqualTo(1);
        assertThat(week.getOpd().trend().get(5).count()).as("a quiet day is a zero, not a gap").isZero();
        assertThat(week.getIpd().trend()).hasSize(7);
        assertThat(week.getIpd().admissions()).isEqualTo(1);

        assertThat(dashboard.getOverview(DashboardRange.LAST_30_DAYS).getOpd().trend()).hasSize(30);
    }

    // ── consultations ────────────────────────────────────────────────────────

    @Test
    void consultationsCountOpdEncountersAndExcludeInpatientRounds() {
        mine = myHospital("OPD");
        medicalRecord(mine, DAY.atTime(10, 0), "OPD");   // walk-in
        medicalRecord(mine, DAY.atTime(11, 0), "OPD");   // appointment-origin: same record type
        medicalRecord(mine, DAY.atTime(12, 0), "IPD");   // ward round, not a consultation
        em.flush();
        em.clear();

        assertThat(dashboard.getOverview(DashboardRange.TODAY).getOpd().consultations())
                .isEqualTo(2);
    }

    // ── beds ─────────────────────────────────────────────────────────────────

    @Test
    void occupancyCountsCleaningAsCapacityAndMaintenanceAsNone() {
        mine = myHospital("IPD");
        bed(mine, BedStatus.OCCUPIED);
        bed(mine, BedStatus.OCCUPIED);
        bed(mine, BedStatus.AVAILABLE);
        bed(mine, BedStatus.CLEANING);
        bed(mine, BedStatus.MAINTENANCE);
        bed(mine, "decommissioned");   // not a status the domain defines
        em.flush();
        em.clear();

        DashboardOverviewDTO.BedsBlock beds = dashboard.getOverview(DashboardRange.TODAY).getBeds();

        assertThat(beds.occupied()).isEqualTo(2);
        assertThat(beds.usableCapacity()).as("all six beds less the one under maintenance").isEqualTo(5);
        assertThat(beds.currentlyAvailable()).as("cleaning is capacity but not free").isEqualTo(1);
        assertThat(beds.cleaning()).isEqualTo(1);
        assertThat(beds.maintenance()).isEqualTo(1);
        assertThat(beds.unknownStatusCount()).isEqualTo(1);
        assertThat(beds.occupancyRate()).as("2 of 5").isEqualTo(40.0);
        assertThat(beds.asOf()).isEqualTo(DAY.atTime(14, 30));
    }

    /**
     * The agreed formula, on the agreed numbers. A bed whose status nobody recognises is still a
     * bed the hospital owns: it cannot be claimed as occupied, available or cleaning, but removing
     * it from the denominator would quietly shrink the hospital and inflate the occupancy figure
     * that gets quoted in meetings. It stays in, and it is reported on its own line.
     */
    @Test
    void anUnknownStatusBedIsStillABedTheHospitalHas() {
        mine = myHospital("IPD");
        for (int i = 0; i < 4; i++) bed(mine, BedStatus.OCCUPIED);
        for (int i = 0; i < 2; i++) bed(mine, BedStatus.AVAILABLE);
        for (int i = 0; i < 2; i++) bed(mine, BedStatus.MAINTENANCE);
        bed(mine, BedStatus.CLEANING);
        bed(mine, "imported-from-old-system");
        em.flush();
        em.clear();

        DashboardOverviewDTO.BedsBlock beds = dashboard.getOverview(DashboardRange.TODAY).getBeds();

        assertThat(beds.usableCapacity()).as("10 beds less 2 under maintenance").isEqualTo(8);
        assertThat(beds.occupied()).isEqualTo(4);
        assertThat(beds.occupancyRate()).as("4 of 8").isEqualTo(50.0);
        assertThat(beds.currentlyAvailable()).as("the unknown bed is not free").isEqualTo(2);
        assertThat(beds.cleaning()).as("nor is it cleaning").isEqualTo(1);
        assertThat(beds.maintenance()).as("nor under maintenance").isEqualTo(2);
        assertThat(beds.unknownStatusCount()).as("it is visible as exactly what it is").isEqualTo(1);
    }

    @Test
    void aHospitalWithNoUsableBedsHasNoOccupancyRateRatherThanZeroPercent() {
        mine = myHospital("IPD");
        bed(mine, BedStatus.MAINTENANCE);
        em.flush();
        em.clear();

        DashboardOverviewDTO.BedsBlock beds = dashboard.getOverview(DashboardRange.TODAY).getBeds();

        assertThat(beds.usableCapacity()).isZero();
        assertThat(beds.occupancyRate()).as("0% would read as an empty hospital").isNull();
    }

    // ── billing and pharmacy ────────────────────────────────────────────────

    @Test
    void collectionSumsPaymentsInTheWindowOnly() {
        mine = myHospital("BILLING");
        payment(mine, "1500.50", DAY.atTime(9, 0));
        payment(mine, "499.50", DAY.atTime(21, 0));
        payment(mine, "9999.00", DAY.minusDays(1).atTime(23, 59, 59));
        em.flush();
        em.clear();

        assertThat(dashboard.getOverview(DashboardRange.TODAY).getBilling().collection())
                .isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    /**
     * One row per invocation, so each answer is 0 or 1 and means one thing. Asserting a total
     * across several rows cannot police the boundary: rows spanning yesterday evening to this
     * afternoon total the same as rows spanning midnight to midnight, so a window shifted by five
     * and a half hours in either direction would still add up. PharmacySaleTimestampIT repeats
     * this against the TIMESTAMP column production really has; here it runs against the schema
     * Hibernate generates, which is where the window arithmetic itself is proven.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2026-09-05T23:59:59, 0",  // last second of yesterday
            "2026-09-06T00:00:00, 1",  // first second of today, inclusive bound
            "2026-09-06T14:30:00, 1",  // the middle of the business day
            "2026-09-06T23:59:59, 1",  // last second of today
            "2026-09-07T00:00:00, 0",  // first second of tomorrow, exclusive bound
    })
    void eachPharmacyBoundaryRowIsCountedOrNotOnItsOwn(String createdAt, long expected) {
        mine = myHospital("PHARMACY");
        pharmacySale(mine, LocalDateTime.parse(createdAt), "POSTED");
        em.flush();
        em.clear();

        assertThat(dashboard.getOverview(DashboardRange.TODAY).getPharmacy().pharmacySales())
                .as("%s against [2026-09-06T00:00, 2026-09-07T00:00)", createdAt)
                .isEqualTo(expected);
    }

    @Test
    void pharmacySalesCountDispensingTransactionsIncludingUnpaidInpatientOnes() {
        mine = myHospital("PHARMACY");
        pharmacySale(mine, DAY.atTime(9, 0), "POSTED");
        PharmacySale unpaidIpd = pharmacySale(mine, DAY.atTime(10, 0), "POSTED");
        unpaidIpd.setPaymentStatus("PENDING");
        unpaidIpd.setSaleType("IPD");
        PharmacySale legacy = pharmacySale(mine, DAY.atTime(11, 0), null); // predates the column
        pharmacySale(mine, DAY.atTime(12, 0), "DRAFT");   // explicitly not posted
        em.flush();
        em.clear();

        assertThat(rawPostingStatus(legacy.getId()))
                .as("the legacy row must really hold NULL, or this test proves nothing")
                .isNull();

        assertThat(dashboard.getOverview(DashboardRange.TODAY).getPharmacy().pharmacySales())
                .as("medicine that left the shelf counts, paid or not; a non-posted row does not")
                .isEqualTo(3);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** The hospital the request is made as. */
    private long myHospital(String... modules) {
        long id = createHospital(modules);
        when(securityHelper.getCurrentHospitalId()).thenReturn(id);
        return id;
    }

    /** A neighbouring tenant, used to prove its data never reaches the caller. */
    private long otherHospital(String... modules) {
        return createHospital(modules);
    }

    private long createHospital(String... modules) {
        Hospital h = new Hospital();
        h.setName("Hospital");
        h.setType(HospitalType.HOSPITAL);
        h.setModules(new java.util.ArrayList<>(List.of(modules)));
        return hospitalRepository.saveAndFlush(h).getId();
    }

    private Patient patient(long hospitalId) {
        Patient p = new Patient();
        p.setHospitalId(hospitalId);
        p.setName("Patient");
        p.setPhone("9800000000");
        p.setGender("MALE");
        p.setIsActive(true);
        return patientRepository.saveAndFlush(p);
    }

    private Doctor doctor(long hospitalId, String specialization) {
        Doctor d = new Doctor();
        d.setHospitalId(hospitalId);
        d.setName("Doctor");
        d.setEmail("doctor" + System.nanoTime() + "@hospital.test");
        d.setPhone("9800000001");
        d.setSpecialization(specialization);
        d.setIsActive(true);
        return doctorRepository.saveAndFlush(d);
    }

    private void opd(Patient patient, Doctor doctor, LocalDateTime at, Opd.VisitType visitType) {
        Opd o = new Opd();
        o.setPatient(patient);
        o.setDoctor(doctor);
        o.setVisitType(visitType);
        o.setStatus(Opd.Status.COMPLETED);
        o.setCreatedAt(at);
        opdRepository.saveAndFlush(o);
    }

    private void medicalRecord(long hospitalId, LocalDateTime at, String visitType) {
        MedicalRecord m = new MedicalRecord();
        m.setHospitalId(hospitalId);
        m.setPatientId(1L);
        m.setDoctorId(1L);
        m.setVisitType(visitType);
        m.setPublicId("MR-" + System.nanoTime());
        MedicalRecord saved = medicalRecordRepository.saveAndFlush(m);
        // @CreationTimestamp wins over anything set in Java, so place the row explicitly.
        em.createNativeQuery("UPDATE medical_records SET created_at = :at WHERE id = :id")
                .setParameter("at", at).setParameter("id", saved.getId()).executeUpdate();
    }

    private void ipdAdmission(long hospitalId, LocalDateTime at) {
        IpdAdmission a = new IpdAdmission();
        a.setHospitalId(hospitalId);
        a.setIpdNumber("IPD-" + System.nanoTime());
        a.setPatientId(1L);
        a.setDoctorId(1L);
        a.setWardId(1L);
        a.setBedId(1L);
        a.setAdmissionType("GENERAL");
        a.setStatus("ADMITTED");
        a.setAdmissionConfirmed(true);
        a.setAdmissionDatetime(at);
        ipdAdmissionRepository.saveAndFlush(a);
    }

    private void bed(long hospitalId, String status) {
        Bed b = new Bed();
        b.setHospitalId(hospitalId);
        b.setWardId(1L);
        b.setBedCode("B" + System.nanoTime());
        b.setStatus(status);
        bedRepository.saveAndFlush(b);
    }

    private void payment(long hospitalId, String amount, LocalDateTime at) {
        BillingPayment p = new BillingPayment();
        p.setHospitalId(hospitalId);
        p.setBillingId(1L);
        p.setAmount(new BigDecimal(amount));
        BillingPayment saved = billingPaymentRepository.saveAndFlush(p);
        em.createNativeQuery("UPDATE billing_payments SET created_at = :at WHERE id = :id")
                .setParameter("at", at).setParameter("id", saved.getId()).executeUpdate();
    }

    /**
     * @param postingStatus null means a row that predates the column. PharmacySale's @PrePersist
     *                      rewrites a null to POSTED, so persisting one and hoping is not enough —
     *                      the null has to be written past the entity, the way ddl-auto adding the
     *                      column to a table that already had rows wrote it.
     */
    private PharmacySale pharmacySale(long hospitalId, LocalDateTime at, String postingStatus) {
        PharmacySale s = new PharmacySale();
        s.setHospitalId(hospitalId);
        s.setPostingStatus(postingStatus == null ? "POSTED" : postingStatus);
        s.setPaymentStatus("PAID");
        s.setBillNumber("PHB-" + System.nanoTime());
        PharmacySale saved = pharmacySaleRepository.saveAndFlush(s);
        em.createNativeQuery("UPDATE pharmacy_sales SET created_at = :at WHERE id = :id")
                .setParameter("at", at).setParameter("id", saved.getId()).executeUpdate();
        if (postingStatus == null) {
            em.createNativeQuery("UPDATE pharmacy_sales SET posting_status = NULL WHERE id = :id")
                    .setParameter("id", saved.getId()).executeUpdate();
        }
        return saved;
    }

    /**
     * Bean Validation forbids persisting a blank specialization, but the column is only NOT NULL,
     * so legacy and imported rows can still hold one. Write it the way they got there.
     */
    private void blankSpeciality(Doctor doctor) {
        em.createNativeQuery("UPDATE doctors SET specialization = '   ' WHERE id = :id")
                .setParameter("id", doctor.getId()).executeUpdate();
    }

    /** Reads the column straight out of the database, past the entity and its defaults. */
    private Object rawPostingStatus(long saleId) {
        return em.createNativeQuery("SELECT posting_status FROM pharmacy_sales WHERE id = :id")
                .setParameter("id", saleId).getSingleResult();
    }
}

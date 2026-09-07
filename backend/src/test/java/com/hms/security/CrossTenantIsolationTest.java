package com.hms.security;

import com.hms.entity.*;
import com.hms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permanent guard against cross-tenant authorization regressions.
 *
 * This audit found that several endpoints loaded a tenant-owned resource by its raw numeric
 * id and never checked the owning hospital, so hospital B could read hospital A's IPD record
 * and prescription PDFs and could administer medication against A's admission. Sequential ids
 * made the resources trivially enumerable.
 *
 * The test seeds two hospitals directly, mints a real JWT for each (the filter is stateless
 * and trusts the token's hospitalId claim), then fires hospital B's token at hospital A's ids.
 * Every cross-tenant read, write, download and delete must be refused (401/403/404); hospital
 * A must still reach its own resources. If any endpoint regresses to a bare findById, the
 * corresponding assertion here flips to 200 and CI fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CrossTenantIsolationTest {

    @LocalServerPort int port;
    @Autowired JwtUtil jwtUtil;

    @Autowired HospitalRepository hospitalRepository;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired WardRepository wardRepository;
    @Autowired BedRepository bedRepository;
    @Autowired IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired BillingRepository billingRepository;
    @Autowired MedicalRecordRepository medicalRecordRepository;
    @Autowired OpdRepository opdRepository;
    @Autowired com.hms.repository.pharmacy.PharmacySaleRepository pharmacySaleRepository;

    private static final List<String> MODULES = List.of(
            "OPD", "IPD", "PHARMACY", "BILLING", "NURSING", "OT",
            "APPOINTMENTS", "REPORTS", "HOSPITAL_INVENTORY", "MEDICAL_INVENTORY");

    private String tokenA, tokenB;
    private Long aIpdId, aBillId, aOpdId, aBedId, aDoctorId, aPharmacySaleId, aWardId;
    private String aPatientPublicId;
    // Phase 1 · G-01b / G-03 — tenant B needs its own ward, bed, patient and doctor so
    // the attack fails for the TENANT reason, not for "no bed" or "doctor not found".
    private Long bWardId, bBedId;
    private Long aOpdIdNoRecord, bOpdId;
    private String bPatientPublicId;

    private String uniq() { return Long.toString(System.nanoTime()); }

    /** Small, unique IPD suffixes. nanoTime() overflows the Integer that
     *  findMaxIpdSequence() returns; real ipd_number suffixes are small sequentials. */
    private static final java.util.concurrent.atomic.AtomicInteger IPD_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(1000);
    private String nextIpdNumber() {
        // Step by 1000: admitFromOpd derives its number from MAX+1 globally, so adjacent
        // fixture values would collide with numbers the application itself generates.
        return "IPD-" + IPD_SEQ.addAndGet(1000);
    }

    /** Seed one hospital and return its id. If withJourney, also create a patient/OPD/IPD/bill. */
    /** An active user of the given tenant. Its id and tokenVersion are what the token carries. */
    private User seedUser(long hospitalId, String slug) {
        User u = new User();
        u.setEmail("admin-" + slug + "-" + uniq() + "@x.com");
        u.setPassword("{noop}x");
        u.setName("Admin " + slug);
        u.setRole("HOSPITAL_ADMIN");
        u.setHospitalId(hospitalId);
        u.setIsActive(true);
        return userRepository.save(u);
    }

    private String tokenFor(User u, long hospitalId) {
        return jwtUtil.generateToken(u.getId(), u.getEmail(), u.getRole(), hospitalId,
                MODULES, null, "HOSPITAL", null, u.getTokenVersion());
    }

    private long seedHospital(String slug, boolean withJourney) {
        Hospital h = new Hospital();
        h.setName("H-" + slug);
        h.setCustomId("HID-" + uniq());
       
        h.setSubscriptionStatus("ACTIVE");
        h.setIsActive(true);
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        long hid = hospitalRepository.save(h).getId();

        if (!withJourney) return hid;

        Doctor doc = new Doctor();
        doc.setName("Dr " + slug); doc.setHospitalId(hid); doc.setIsActive(true);
        doc.setEmail("doc-" + uniq() + "@x.com"); doc.setPublicId("dpub-" + uniq());
        doc.setPhone("9800000001"); doc.setSpecialization("Gen");
        aDoctorId = doctorRepository.save(doc).getId();

        Patient p = new Patient();
        aPatientPublicId = "ppub-" + uniq();
        p.setName("Pat " + slug); p.setHospitalId(hid); p.setPublicId(aPatientPublicId);
        p.setGender("MALE"); p.setPhone("9900000001"); p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        long pid = patientRepository.save(p).getId();

        Ward w = new Ward();
        w.setWardName("W"); w.setHospitalId(hid); w.setBedPrice(new BigDecimal("1000")); w.setTotalBeds(1);
        long wid = wardRepository.save(w).getWardId();
        aWardId = wid;

        Bed bed = new Bed();
        bed.setHospitalId(hid); bed.setWardId(wid); bed.setBedCode("B1"); bed.setStatus("occupied");
        aBedId = bedRepository.save(bed).getBedId();

        Opd opd = new Opd();
        opd.setCaseId("OPD-" + uniq()); opd.setIpdAdmitRecommended(false);
        opd.setPatient(p);
        aOpdId = opdRepository.save(opd).getId();

        Opd opd2 = new Opd();
        opd2.setCaseId("OPD-" + uniq()); opd2.setIpdAdmitRecommended(false);
        opd2.setPatient(p); opd2.setDoctor(doc);
        aOpdIdNoRecord = opdRepository.save(opd2).getId();

        MedicalRecord mr = new MedicalRecord();
        mr.setPublicId("mrpub-" + uniq()); mr.setHospitalId(hid); mr.setPatientId(pid);
        mr.setDoctorId(aDoctorId); mr.setVisitType("OPD"); mr.setOpdId(aOpdId);
        medicalRecordRepository.save(mr);

        IpdAdmission ipd = new IpdAdmission();
        ipd.setIpdNumber(nextIpdNumber()); ipd.setPatientId(pid); ipd.setDoctorId(aDoctorId);
        ipd.setHospitalId(hid); ipd.setAdmissionType("ELECTIVE"); ipd.setStatus("ADMITTED");
        ipd.setAdmissionDatetime(LocalDateTime.now()); ipd.setWardId(wid); ipd.setBedId(aBedId);
        ipd.setAdmissionConfirmed(true);
        aIpdId = ipdAdmissionRepository.save(ipd).getId();

        Billing b = new Billing();
        b.setCustomId("BIL-" + uniq()); b.setHospitalId(hid); b.setPatientId(pid); b.setDoctorId(aDoctorId);
        b.setBillingType("IPD"); b.setAmount(new BigDecimal("1000")); b.setPaymentStatus("PENDING");
        aBillId = billingRepository.save(b).getId();

        com.hms.entity.pharmacy.PharmacySale sale = new com.hms.entity.pharmacy.PharmacySale();
        sale.setHospitalId(hid); sale.setBillNumber("PHB-" + uniq());
        sale.setNetAmount(new BigDecimal("100")); sale.setPatientName("Walk-in");
        aPharmacySaleId = pharmacySaleRepository.save(sale).getId();

        return hid;
    }

    @BeforeEach
    void setUp() {
        long hidA = seedHospital("alpha", true);
        long hidB = seedHospital("bravo", false);
        // Real User rows, and tokens minted from them. JwtAuthenticationFilter revalidates every
        // request against userRepository.findActiveTokenVersion(userId), so a token for an id
        // that was never persisted is refused at the filter -- the request arrives
        // unauthenticated and every tenant assertion below reads 401 instead of the 404 it is
        // testing for. Hardcoded ids happened to work only while the shared in-memory database
        // held a User(id=1) left by another test class; that is ordering luck, not a fixture.
        User userA = seedUser(hidA, "alpha");
        User userB = seedUser(hidB, "bravo");
        tokenA = tokenFor(userA, hidA);
        tokenB = tokenFor(userB, hidB);
        seedBravoJourney(hidB);
    }

    /**
     * Transport only -- the assertions, endpoints and tenant semantics below are unchanged.
     *
     * <p>TestRestTemplate cannot be used here any more. Its SimpleClientHttpRequestFactory
     * streams the request body, and 233b66e made the unauthenticated path answer 401 with a
     * body (SecurityConfig -> SecurityErrorResponder). HttpURLConnection reacts to the 401 by
     * trying to replay the request, which a streamed PUT/DELETE cannot do, and throws
     * "cannot retry due to server authentication, in streaming mode" before any status is
     * observed -- so the refusal this class exists to assert became unobservable.
     *
     * <p>java.net.http.HttpClient does not retry on 401, so the status is returned as sent.
     */
    private ResponseEntity<String> call(HttpMethod method, String path, String token, String body) {
        try {
            java.net.http.HttpRequest.BodyPublisher publisher = body == null
                    ? java.net.http.HttpRequest.BodyPublishers.noBody()
                    : java.net.http.HttpRequest.BodyPublishers.ofString(body);

            java.net.http.HttpRequest.Builder req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + token)
                    .method(method.name(), publisher);
            if (body != null) req.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);

            java.net.http.HttpResponse<String> res = java.net.http.HttpClient.newHttpClient()
                    .send(req.build(), java.net.http.HttpResponse.BodyHandlers.ofString());

            return ResponseEntity.status(res.statusCode()).body(res.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("HTTP call failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted: " + method + " " + path, e);
        }
    }


    private void assertRefused(HttpMethod m, String path, String body) {
        HttpStatusCode s = call(m, path, tokenB, body).getStatusCode();
        assertThat(s.value())
                .as("cross-tenant %s %s must be refused, got %s", m, path, s)
                .isIn(401, 403, 404);
    }

    @Test
    void crossTenant_reads_areRefused() {
        assertRefused(HttpMethod.GET, "/hospital/ipd/" + aIpdId, null);
        assertRefused(HttpMethod.GET, "/hospital/patients/opd/" + aOpdId + "/medicines/pdf", null);
        assertRefused(HttpMethod.GET, "/hospital/patients/ipd/" + aIpdId + "/medicines/pdf", null);
        assertRefused(HttpMethod.GET, "/hospital/patients/ipd/" + aIpdId + "/prescription/pdf", null);
        assertRefused(HttpMethod.GET, "/hospital/doctors/prescription/opd/" + aOpdId + "/pdf", null);
        assertRefused(HttpMethod.GET, "/hospital/billing/" + aBillId + "/pdf", null);
        assertRefused(HttpMethod.GET, "/hospital/billing/ipd/" + aIpdId + "/bill", null);
        assertRefused(HttpMethod.GET, "/hospital/opd/queue/doctor/" + aDoctorId, null);
        // Pharmacy ERP surface: a sale invoice must be scoped to its owning pharmacy tenant.
        assertRefused(HttpMethod.GET, "/pharmacy/sales/" + aPharmacySaleId, null);
        assertRefused(HttpMethod.GET, "/pharmacy/sales/" + aPharmacySaleId + "/pdf", null);
    }

    /**
     * The admin Overview takes no id at all: it reads the whole tenant from the token's
     * hospitalId, so a lost WHERE clause would not 404 anywhere — it would quietly hand
     * hospital B hospital A's numbers. Beds are asserted because they are the one block with
     * no date range, so the assertion cannot drift with the clock: alpha's only bed is
     * occupied, bravo's only bed is available, and neither may see the other's.
     */
    @Test
    void crossTenant_dashboardOverview_countsOnlyTheCallersOwnHospital() {
        JsonNode alpha = overviewFor(tokenA);
        JsonNode bravo = overviewFor(tokenB);

        assertThat(alpha.path("beds").path("occupied").asLong()).as("alpha's own bed").isEqualTo(1);
        assertThat(alpha.path("beds").path("currentlyAvailable").asLong())
                .as("alpha must not count bravo's free bed").isEqualTo(0);

        assertThat(bravo.path("beds").path("currentlyAvailable").asLong()).as("bravo's own bed").isEqualTo(1);
        assertThat(bravo.path("beds").path("occupied").asLong())
                .as("bravo must not see alpha's occupied bed").isEqualTo(0);
    }

    /**
     * The Overview describes wards, beds and inpatient admissions. Being a CORE controller is what
     * lets any facility type reach it — FacilityAccessAspect waves CORE through — so the hospital
     * restriction has to be stated with @TenantType or it is not stated at all.
     */
    @Test
    void aClinicTenantCannotOpenTheHospitalOverview() {
        long clinicId = seedHospital("clinic", false);
        User clinicAdmin = seedUser(clinicId, "clinic");
        String clinicToken = jwtUtil.generateToken(clinicAdmin.getId(), clinicAdmin.getEmail(),
                clinicAdmin.getRole(), clinicId, MODULES, null, "CLINIC", null,
                clinicAdmin.getTokenVersion());

        int status = call(HttpMethod.GET, "/hospital/dashboard/overview", clinicToken, null)
                .getStatusCode().value();

        assertThat(status).as("a clinic has no wards to report on").isEqualTo(403);
    }

    private JsonNode overviewFor(String token) {
        ResponseEntity<String> res = call(HttpMethod.GET, "/hospital/dashboard/overview", token, null);
        assertThat(res.getStatusCode().value()).as("the owner reads its own overview").isEqualTo(200);
        try {
            return new ObjectMapper().readTree(res.getBody());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("overview response was not JSON: " + res.getBody(), e);
        }
    }

    @Test
    void crossTenant_writes_areRefused() {
        assertRefused(HttpMethod.POST, "/hospital/billing/" + aBillId + "/pay", "{\"amount\":1,\"mode\":\"CASH\"}");
        // Empty list keeps @Valid happy so the request reaches the service's tenant check
        // (a non-empty item would 400 on body validation before the check is exercised).
        assertRefused(HttpMethod.POST, "/hospital/ipd/" + aIpdId + "/administer", "{\"administeredItems\":[]}");
        assertRefused(HttpMethod.POST, "/hospital/ipd/" + aIpdId + "/confirm-discharge", "{}");
        assertRefused(HttpMethod.POST, "/hospital/beds/" + aBedId + "/maintenance", "{}");
        assertRefused(HttpMethod.POST, "/hospital/beds/" + aBedId + "/available", "{}");
    }

    /**
     * Phase 2.1 — these four sites compared hospital ids but threw a generic "Access denied",
     * so the Phase 2 message-driven sweep classified them as role failures and they returned
     * 403. A 403 confirms the record exists in another hospital, and `assertRefused` above
     * accepts 401/403/404 so it could never have caught the difference. These assert the
     * exact status, and pair each with the missing-id case to prove the two are identical.
     */
    @Test
    void g0x_crossTenantTenantChecksWithGenericMessages_are404_not403() {
        int missingWard = call(HttpMethod.DELETE, "/hospital/wards/99999999", tokenB, null)
                .getStatusCode().value();
        int foreignWardDelete = call(HttpMethod.DELETE, "/hospital/wards/" + aWardId, tokenB, null)
                .getStatusCode().value();
        int foreignWardUpdate = call(HttpMethod.PUT, "/hospital/wards/" + aWardId, tokenB,
                "{\"wardName\":\"x\",\"totalBeds\":1,\"bedPrice\":100}").getStatusCode().value();
        int missingBed = call(HttpMethod.PUT, "/hospital/beds/99999999", tokenB,
                "{\"status\":\"maintenance\"}").getStatusCode().value();
        int foreignBed = call(HttpMethod.PUT, "/hospital/beds/" + aBedId, tokenB,
                "{\"status\":\"maintenance\"}").getStatusCode().value();

        assertThat(foreignWardDelete).as("WardService#deleteWard: tenant check must be 404").isEqualTo(404);
        assertThat(foreignWardUpdate).as("WardService#updateWard: tenant check must be 404").isEqualTo(404);
        assertThat(foreignBed).as("BedService#updateStatus: tenant check must be 404").isEqualTo(404);
        assertThat(foreignWardDelete).as("a foreign ward is answered exactly like a missing one")
                .isEqualTo(missingWard);
        assertThat(foreignBed).as("a foreign bed is answered exactly like a missing one")
                .isEqualTo(missingBed);
    }

    @Test
    void crossTenant_deletes_areRefused() {
        // Patient delete is by public id and uses a tenant-scoped finder; a foreign tenant
        // must not be able to soft-delete another hospital's patient record.
        assertRefused(HttpMethod.DELETE, "/hospital/patients/" + aPatientPublicId, null);
    }

    @Test
    void sameTenant_ownIpd_isReachable() {
        // Positive control: the owner is not locked out by the tenant checks above.
        HttpStatusCode s = call(HttpMethod.GET, "/hospital/ipd/" + aIpdId, tokenA, null).getStatusCode();
        assertThat(s.value()).as("owner reading its own IPD").isEqualTo(200);
    }

    // ── Phase 1 · G-01 / G-02 ────────────────────────────────────────────────
    // Cross-tenant OPD read and both OPD PDF paths. These assert 404 exactly
    // (not isIn(401,403,404)): a cross-tenant id must be indistinguishable from
    // a non-existent one, per HMS_SYSTEM_DESIGN §2 / principle 7.

    private int statusOf(String path) {
        return call(HttpMethod.GET, path, tokenB, null).getStatusCode().value();
    }

    @Test
    void g01_crossTenantOpdRead_is404() {
        assertThat(statusOf("/hospital/opd/" + aOpdId))
                .as("G-01: tenant B reading tenant A's OPD by id").isEqualTo(404);
    }

    @Test
    void g02_crossTenantOpdCasePaperPdf_is404() {
        assertThat(statusOf("/hospital/opd/" + aOpdId + "/pdf"))
                .as("G-02: tenant B fetching tenant A's case-paper PDF").isEqualTo(404);
    }

    @Test
    void g02_crossTenantOpdDocumentsPdf_is404() {
        assertThat(statusOf("/hospital/opd/" + aOpdId + "/documents/pdf"))
                .as("G-02: tenant B fetching tenant A's combined documents PDF").isEqualTo(404);
    }

    @Test
    void g01_sameTenantOpdRead_stillWorks() {
        assertThat(call(HttpMethod.GET, "/hospital/opd/" + aOpdId, tokenA, null)
                .getStatusCode().value())
                .as("positive control: owner can still read its own OPD").isEqualTo(200);
    }

    /** Minimal own-tenant assets for B: available bed to admit into, patient, and a
     *  Doctor whose email matches tokenB so DoctorService can resolve it. */
    private void seedBravoJourney(long hidB) {
        Doctor d = new Doctor();
        d.setName("Dr bravo"); d.setHospitalId(hidB); d.setIsActive(true);
        d.setEmail("admin@bravo.com"); d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000002"); d.setSpecialization("Gen");
        doctorRepository.save(d);

        Patient p = new Patient();
        bPatientPublicId = "ppub-" + uniq();
        p.setName("Pat bravo"); p.setHospitalId(hidB); p.setPublicId(bPatientPublicId);
        p.setGender("MALE"); p.setPhone("9900000002"); p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1985, 1, 1));
        patientRepository.save(p);

        Ward w = new Ward();
        w.setWardName("WB"); w.setHospitalId(hidB);
        w.setBedPrice(new BigDecimal("1000")); w.setTotalBeds(1);
        w.setInchargeNurseId(9999L); // NURSING gate: incharge-less wards 400 before the OPD is used
        bWardId = wardRepository.save(w).getWardId();

        Opd bOpd = new Opd();
        bOpd.setCaseId("OPD-" + uniq()); bOpd.setIpdAdmitRecommended(false);
        bOpd.setPatient(p); bOpd.setDoctor(d);
        bOpdId = opdRepository.save(bOpd).getId();

        Bed b = new Bed();
        b.setHospitalId(hidB); b.setWardId(bWardId); b.setBedCode("BB1"); b.setStatus("available");
        bBedId = bedRepository.save(b).getBedId();
    }

    // ── G-01b · cross-tenant IPD admission via a client-supplied OPD id ──────────

    @Test
    void g01b_crossTenantAdmitFromOpd_is404_andMutatesNothing() {
        long admissionsBefore = ipdAdmissionRepository.count();
        long billsBefore      = billingRepository.count();
        String bedBefore      = bedRepository.findById(bBedId).orElseThrow().getStatus();
        boolean opdFlagBefore = opdRepository.findById(aOpdIdNoRecord).orElseThrow().getIpdAdmitRecommended();

        String body = "{\"opdId\":" + aOpdIdNoRecord + ",\"wardId\":" + bWardId + ",\"bedId\":" + bBedId
                    + ",\"admissionType\":\"ELECTIVE\",\"primaryDiagnosis\":\"x\"}";
        int status = call(HttpMethod.POST, "/hospital/ipd/admit", tokenB, body).getStatusCode().value();

        assertThat(status).as("G-01b: tenant B admitting using tenant A's OPD id").isEqualTo(404);
        assertThat(ipdAdmissionRepository.count()).as("no admission created").isEqualTo(admissionsBefore);
        assertThat(billingRepository.count()).as("no bill created").isEqualTo(billsBefore);
        assertThat(bedRepository.findById(bBedId).orElseThrow().getStatus())
                .as("tenant B's bed not occupied").isEqualTo(bedBefore);
        assertThat(opdRepository.findById(aOpdIdNoRecord).orElseThrow().getIpdAdmitRecommended())
                .as("tenant A's OPD untouched").isEqualTo(opdFlagBefore);
    }

    // ── G-03 · cross-tenant consultation write ───────────────────────────────────

    @Test
    void g03_crossTenantConsultationWrite_is404_andMutatesNothing() {
        long recordsBefore = medicalRecordRepository.count();
        boolean opdFlagBefore = opdRepository.findById(aOpdId).orElseThrow().getIpdAdmitRecommended();
        String opdStatusBefore = String.valueOf(opdRepository.findById(aOpdIdNoRecord).orElseThrow().getStatus());

        String body = "{\"patientId\":\"" + bPatientPublicId + "\",\"opdId\":" + aOpdIdNoRecord
                    + ",\"ipdAdmitRecommended\":true,\"diagnosis\":\"d\",\"symptoms\":\"s\"}";
        int status = call(HttpMethod.POST, "/hospital/doctors/consultation", tokenB, body)
                .getStatusCode().value();

        assertThat(status).as("G-03: tenant B writing a consultation against tenant A's OPD")
                .isEqualTo(404);
        assertThat(opdRepository.findById(aOpdIdNoRecord).orElseThrow().getIpdAdmitRecommended())
                .as("tenant A's OPD admit-flag untouched").isEqualTo(opdFlagBefore);
        assertThat(String.valueOf(opdRepository.findById(aOpdIdNoRecord).orElseThrow().getStatus()))
                .as("tenant A's OPD status untouched").isEqualTo(opdStatusBefore);
        assertThat(medicalRecordRepository.count())
                .as("no medical record written into tenant A's OPD").isEqualTo(recordsBefore);
    }

    @Test
    void g01b_sameTenantAdmitFromOwnOpd_stillWorks() {
        String body = "{\"opdId\":" + bOpdId + ",\"wardId\":" + bWardId + ",\"bedId\":" + bBedId
                    + ",\"admissionType\":\"ELECTIVE\",\"primaryDiagnosis\":\"x\"}";
        assertThat(call(HttpMethod.POST, "/hospital/ipd/admit", tokenB, body).getStatusCode().value())
                .as("positive control: tenant B admits from its OWN OPD").isEqualTo(200);
    }
}

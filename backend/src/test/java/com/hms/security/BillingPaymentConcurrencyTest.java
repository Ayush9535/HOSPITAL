package com.hms.security;

import com.hms.entity.Billing;
import com.hms.entity.BillingItem;
import com.hms.entity.BillingPayment;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.repository.BillingItemRepository;
import com.hms.repository.BillingPaymentRepository;
import com.hms.repository.BillingRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing P0: a bill must never collect more than it is owed, however the request arrives.
 *
 * <p>/pay read the already-collected total and inserted the new payment without a transaction or
 * a lock. Two concurrent calls -- a double-clicked "Paid" button (the UI tracked a saving flag but
 * never disabled the button with it), or a client retry after a timeout -- both read the same
 * figure, both concluded the amount fitted inside the outstanding balance, and both inserted. The
 * patient was charged twice and nothing reported an error.
 *
 * <p>Driven through the real endpoint against a real database: the guarantee lives in a row lock,
 * which a mocked repository cannot demonstrate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BillingPaymentConcurrencyTest {

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired UserRepository userRepository;
    @Autowired BillingRepository billingRepository;
    @Autowired BillingItemRepository billingItemRepository;
    @Autowired BillingPaymentRepository billingPaymentRepository;

    private static final List<String> MODULES = List.of("OPD", "IPD", "PHARMACY", "BILLING");
    private static final BigDecimal BILL_TOTAL = new BigDecimal("1000.00");

    private String token;
    private String foreignToken;
    private Long billingId;

    private String uniq() { return Long.toString(System.nanoTime()); }

    private long seedHospital(String slug) {
        Hospital h = new Hospital();
        h.setName("H " + slug); h.setCustomId("HID-" + uniq());
        h.setSubscriptionStatus("ACTIVE"); h.setIsActive(true);
        h.setModules(MODULES); h.setIsSingleDoctor(false);
        return hospitalRepository.save(h).getId();
    }

    private String adminTokenFor(long hid, String slug) {
        User u = new User();
        u.setEmail("admin-" + uniq() + "@example.test");
        u.setPassword("test-password-hash"); u.setName("Admin " + slug);
        u.setRole("HOSPITAL_ADMIN"); u.setHospitalId(hid);
        u.setIsActive(true); u.setTokenVersion(0);
        u = userRepository.save(u);
        return jwtUtil.generateToken(u.getId(), u.getEmail(), u.getRole(), hid,
                MODULES, null, "HOSPITAL", null, u.getTokenVersion());
    }

    @BeforeEach
    void setUp() {
        long hid = seedHospital("alpha");
        long foreignHid = seedHospital("bravo");

        Doctor d = new Doctor();
        d.setName("Dr alpha"); d.setHospitalId(hid); d.setIsActive(true);
        d.setEmail("doc-" + uniq() + "@example.test"); d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000001"); d.setSpecialization("Gen");
        Long docId = doctorRepository.save(d).getId();

        Patient p = new Patient();
        p.setName("Pat alpha"); p.setHospitalId(hid); p.setPublicId("ppub-" + uniq());
        p.setGender("MALE"); p.setPhone("9900000001"); p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        Long patId = patientRepository.save(p).getId();

        Billing b = new Billing();
        b.setHospitalId(hid); b.setPatientId(patId); b.setDoctorId(docId);
        b.setBillingType("OPD"); b.setAmount(BILL_TOTAL); b.setPaymentStatus("PENDING");
        billingId = billingRepository.save(b).getId();

        BillingItem item = new BillingItem();
        item.setBillingId(billingId); item.setHospitalId(hid);
        item.setDescription("Consultation"); item.setAmount(BILL_TOTAL);
        billingItemRepository.save(item);

        token = adminTokenFor(hid, "alpha");
        foreignToken = adminTokenFor(foreignHid, "bravo");
    }

    private ResponseEntity<String> pay(String bearer, String amount) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(bearer);
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"amount\":" + amount + ",\"mode\":\"CASH\"}";
        return rest.exchange("/hospital/billing/" + billingId + "/pay", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    private BigDecimal totalCollected() {
        BigDecimal paid = BigDecimal.ZERO;
        for (BillingPayment p : billingPaymentRepository.findByBillingId(billingId)) {
            if (p.getAmount() != null) paid = paid.add(p.getAmount());
        }
        return paid;
    }

    /** The reported double-click: six simultaneous full payments must collect the bill once. */
    @Test
    void concurrentFullPayments_collectTheBillExactlyOnce() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> pay(token, "1000.00").getStatusCode().value());
        }
        List<Future<Integer>> results = pool.invokeAll(jobs);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int accepted = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 200) accepted++;
        }

        assertThat(accepted).as("exactly one of the concurrent payments may be accepted").isEqualTo(1);
        assertThat(totalCollected())
                .as("the patient must never be charged more than the bill")
                .isEqualByComparingTo(BILL_TOTAL);
    }

    /** Partial payments race too: four concurrent 300s against a 1000 bill must not exceed it. */
    @Test
    void concurrentPartialPayments_neverExceedTheBalance() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> pay(token, "300.00").getStatusCode().value());
        }
        pool.invokeAll(jobs);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(totalCollected())
                .as("collected must never exceed the bill total")
                .isLessThanOrEqualTo(BILL_TOTAL);
    }

    /**
     * "Mark as Paid" back-fills whatever is still outstanding into the ledger. Clicked twice, it
     * must still record the bill once.
     */
    @Test
    void concurrentMarkAsPaid_backfillsTheLedgerOnce() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> {
                HttpHeaders h = new HttpHeaders();
                h.setBearerAuth(token);
                return rest.exchange("/hospital/billing/" + billingId + "/status?status=PAID&paymentMethod=CASH",
                        HttpMethod.PUT, new HttpEntity<>(h), String.class).getStatusCode().value();
            });
        }
        pool.invokeAll(jobs);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(billingPaymentRepository.findByBillingId(billingId))
                .as("one settlement, not one per click").hasSize(1);
        assertThat(totalCollected()).isEqualByComparingTo(BILL_TOTAL);
    }

    /** Sequential overpayment is a state conflict, reported as 409 and never persisted. */
    @Test
    void payingMoreThanTheBalance_is409_andCollectsNothingExtra() {
        assertThat(pay(token, "1000.00").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> second = pay(token, "50.00");
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(totalCollected()).isEqualByComparingTo(BILL_TOTAL);
    }

    /** Another hospital must not be able to settle this bill. */
    @Test
    void aForeignHospitalCannotPayThisBill() {
        ResponseEntity<String> res = pay(foreignToken, "100.00");

        assertThat(res.getStatusCode().value()).isNotEqualTo(200);
        assertThat(totalCollected()).as("no money posted by the foreign tenant").isZero();
    }

    /**
     * A freshly onboarded hospital that has never opened Settings must survive its staff arriving
     * at the billing screen together.
     *
     * <p>The access check used to INSERT the missing hospital_settings row on this read path;
     * hospital_id is unique there, so simultaneous first requests collided on the index and the
     * losers were rejected outright.
     */
    @Test
    void concurrentFirstBillingRequests_fromAHospitalWithNoSettingsRow_allSucceed() throws Exception {
        long freshHid = seedHospital("fresh");
        String freshToken = adminTokenFor(freshHid, "fresh");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> {
                HttpHeaders h = new HttpHeaders();
                h.setBearerAuth(freshToken);
                return rest.exchange("/hospital/billing", HttpMethod.GET,
                        new HttpEntity<>(h), String.class).getStatusCode().value();
            });
        }
        List<Future<Integer>> results = pool.invokeAll(jobs);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        for (Future<Integer> f : results) {
            assertThat(f.get()).as("every concurrent first read must be served").isEqualTo(200);
        }
    }

    /** A non-positive amount is a malformed request, not a conflict. */
    @Test
    void nonPositiveAmountIsRejected() {
        assertThat(pay(token, "0").getStatusCode().value()).isEqualTo(400);
        assertThat(totalCollected()).isZero();
    }
}

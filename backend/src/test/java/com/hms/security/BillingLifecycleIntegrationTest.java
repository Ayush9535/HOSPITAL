package com.hms.security;

import com.hms.entity.Bed;
import com.hms.entity.Billing;
import com.hms.entity.BillingItem;
import com.hms.entity.BillingPayment;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.BillingItemRepository;
import com.hms.repository.BillingPaymentRepository;
import com.hms.repository.BillingRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.WardRepository;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One patient carried the whole way through the money path, against a real persisted database and
 * through the real endpoints: OPD -> IPD admission -> bill -> line items -> partial payment ->
 * an extra charge added afterwards -> final payment -> discharge -> printed invoice.
 *
 * <p>What this is guarding is not any single endpoint but the arithmetic staying reconciled across
 * all of them: at every step gross, collected, outstanding and status must agree with the line
 * items and the payment ledger, and must still agree when re-read from scratch rather than from
 * whatever the write path happened to return.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BillingLifecycleIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired UserRepository userRepository;
    @Autowired WardRepository wardRepository;
    @Autowired BedRepository bedRepository;
    @Autowired OpdRepository opdRepository;
    @Autowired IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired BillingRepository billingRepository;
    @Autowired BillingItemRepository billingItemRepository;
    @Autowired BillingPaymentRepository billingPaymentRepository;

    private static final List<String> MODULES = List.of("OPD", "IPD", "PHARMACY", "BILLING");
    private static final BigDecimal BED_PRICE = new BigDecimal("1500");

    private String token;
    private Long opdId, wardId, bedId;

    private String uniq() { return Long.toString(System.nanoTime()); }

    @BeforeEach
    void setUp() {
        Hospital h = new Hospital();
        h.setName("H lifecycle"); h.setCustomId("HID-" + uniq());
        h.setSubscriptionStatus("ACTIVE"); h.setIsActive(true);
        h.setModules(MODULES); h.setIsSingleDoctor(false);
        long hid = hospitalRepository.save(h).getId();

        Doctor d = new Doctor();
        d.setName("Dr lifecycle"); d.setHospitalId(hid); d.setIsActive(true);
        d.setEmail("doc-" + uniq() + "@example.test"); d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000001"); d.setSpecialization("Gen");
        doctorRepository.save(d);

        Patient p = new Patient();
        p.setName("Pat lifecycle"); p.setHospitalId(hid); p.setPublicId("ppub-" + uniq());
        p.setGender("MALE"); p.setPhone("9900000001"); p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        patientRepository.save(p);

        Ward w = new Ward();
        w.setWardName("W lifecycle"); w.setHospitalId(hid);
        w.setBedPrice(BED_PRICE); w.setTotalBeds(1);
        wardId = wardRepository.save(w).getWardId();

        Bed b = new Bed();
        b.setHospitalId(hid); b.setWardId(wardId); b.setBedCode("BED-" + uniq()); b.setStatus("available");
        bedId = bedRepository.save(b).getBedId();

        Opd o = new Opd();
        o.setCaseId("OPD-" + uniq()); o.setIpdAdmitRecommended(true);
        o.setPatient(p); o.setDoctor(d);
        opdId = opdRepository.save(o).getId();

        User admin = new User();
        admin.setEmail("admin-" + uniq() + "@example.test");
        admin.setPassword("test-password-hash"); admin.setName("Admin");
        admin.setRole("HOSPITAL_ADMIN"); admin.setHospitalId(hid);
        admin.setIsActive(true); admin.setTokenVersion(0);
        admin = userRepository.save(admin);
        token = jwtUtil.generateToken(admin.getId(), admin.getEmail(), admin.getRole(), hid,
                MODULES, null, "HOSPITAL", null, admin.getTokenVersion());
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> post(String path, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, authHeaders()), String.class);
    }

    private ResponseEntity<String> put(String path, String body) {
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, authHeaders()), String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ipdBill(long ipdId) {
        ResponseEntity<Map> res = rest.exchange("/hospital/billing/ipd/" + ipdId + "/bill",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody();
    }

    private BigDecimal money(Object raw) {
        return new BigDecimal(String.valueOf(raw));
    }

    /** Gross / collected / outstanding recomputed from the stored rows, not from any response. */
    private void assertReconciles(long billId, BigDecimal gross, BigDecimal collected, String status) {
        BigDecimal itemSum = BigDecimal.ZERO;
        for (BillingItem it : billingItemRepository.findByBillingId(billId)) {
            if (it.getAmount() != null) itemSum = itemSum.add(it.getAmount());
        }
        BigDecimal ledger = BigDecimal.ZERO;
        for (BillingPayment p : billingPaymentRepository.findByBillingId(billId)) {
            if (p.getAmount() != null) ledger = ledger.add(p.getAmount());
        }
        Billing stored = billingRepository.findById(billId).orElseThrow();

        assertThat(itemSum).as("line items sum to gross").isEqualByComparingTo(gross);
        assertThat(ledger).as("payment ledger sums to collected").isEqualByComparingTo(collected);
        assertThat(stored.getAmount()).as("stored bill total tracks its line items")
                .isEqualByComparingTo(gross);
        assertThat(stored.getPaymentStatus()).as("status is derived, not asserted").isEqualTo(status);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPatientCarriedFromOpdThroughIpdToDischarge_keepsItsBillReconciled() {
        // --- OPD -> IPD. Admission opens the IPD bill and charges the bed.
        String admitBody = "{\"opdId\":" + opdId + ",\"wardId\":" + wardId + ",\"bedId\":" + bedId
                + ",\"admissionType\":\"ELECTIVE\",\"primaryDiagnosis\":\"obs\"}";
        ResponseEntity<String> admitted = post("/hospital/ipd/admit", admitBody);
        assertThat(admitted.getStatusCode().value()).isEqualTo(200);
        long ipdId = ipdAdmissionRepository.findAll().stream()
                .filter(a -> a.getBedId() != null && a.getBedId().equals(bedId))
                .findFirst().orElseThrow().getId();

        Map<String, Object> bill = ipdBill(ipdId);
        long billId = ((Number) bill.get("billingId")).longValue();
        assertThat(money(bill.get("totalAmount"))).isEqualByComparingTo(BED_PRICE);
        assertThat(money(bill.get("paidAmount"))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(money(bill.get("balance"))).isEqualByComparingTo(BED_PRICE);
        assertReconciles(billId, BED_PRICE, BigDecimal.ZERO, "PENDING");

        // --- Reception writes the bill up: bed + a procedure. 1500 + 2000 = 3500.
        assertThat(put("/hospital/billing/" + billId + "/items",
                "[{\"name\":\"Bed charges\",\"defaultAmount\":1500},"
                        + "{\"name\":\"Procedure\",\"defaultAmount\":2000}]")
                .getStatusCode().value()).isEqualTo(200);
        assertReconciles(billId, new BigDecimal("3500"), BigDecimal.ZERO, "PENDING");

        // --- Part payment. The bill owes a balance, so it must read PARTIAL, not PAID.
        assertThat(post("/hospital/billing/" + billId + "/pay",
                "{\"amount\":1000.00,\"mode\":\"CASH\"}").getStatusCode().value()).isEqualTo(200);
        assertReconciles(billId, new BigDecimal("3500"), new BigDecimal("1000"), "PARTIAL");

        Map<String, Object> afterPartial = ipdBill(ipdId);
        assertThat(money(afterPartial.get("totalAmount"))).isEqualByComparingTo("3500");
        assertThat(money(afterPartial.get("paidAmount"))).isEqualByComparingTo("1000");
        assertThat(money(afterPartial.get("balance"))).isEqualByComparingTo("2500");

        // --- A further charge lands after money was already collected. The already-collected
        // 1000 must survive the rewrite, and the bill must stay PARTIAL against the new, higher
        // total rather than being dragged to PAID or reset to PENDING.
        assertThat(put("/hospital/billing/" + billId + "/items",
                "[{\"name\":\"Bed charges\",\"defaultAmount\":1500},"
                        + "{\"name\":\"Procedure\",\"defaultAmount\":2000},"
                        + "{\"name\":\"Dressing\",\"defaultAmount\":500}]")
                .getStatusCode().value()).isEqualTo(200);
        assertReconciles(billId, new BigDecimal("4000"), new BigDecimal("1000"), "PARTIAL");

        // --- Settling the remainder. Paying a rupee more than is owed is refused.
        assertThat(post("/hospital/billing/" + billId + "/pay",
                "{\"amount\":3001.00,\"mode\":\"CASH\"}").getStatusCode().value()).isEqualTo(409);
        assertThat(post("/hospital/billing/" + billId + "/pay",
                "{\"amount\":3000.00,\"mode\":\"CASH\"}").getStatusCode().value()).isEqualTo(200);
        assertReconciles(billId, new BigDecimal("4000"), new BigDecimal("4000"), "PAID");

        // --- Discharge. Plan, then confirm; confirming closes the bill.
        assertThat(post("/hospital/ipd/" + ipdId + "/plan-discharge",
                "{\"finalDiagnosis\":\"resolved\",\"treatmentGiven\":\"observed\"}")
                .getStatusCode().value()).isEqualTo(200);
        assertThat(post("/hospital/ipd/" + ipdId + "/confirm-discharge", "{}")
                .getStatusCode().value()).isEqualTo(200);

        assertThat(ipdAdmissionRepository.findById(ipdId).orElseThrow().getStatus())
                .isEqualTo("DISCHARGED");
        assertReconciles(billId, new BigDecimal("4000"), new BigDecimal("4000"), "CLOSED");

        // --- The invoice prints, and nothing about the settled bill moved while it did.
        ResponseEntity<byte[]> pdf = rest.exchange("/hospital/billing/" + billId + "/pdf",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), byte[].class);
        assertThat(pdf.getStatusCode().value()).isEqualTo(200);
        assertThat(pdf.getBody()).isNotEmpty();
        assertThat(new String(pdf.getBody(), 0, 4)).as("a real PDF, not an error page").isEqualTo("%PDF");

        // --- Re-read everything cold. This is the check that matters after a page refresh.
        Map<String, Object> finalBill = ipdBill(ipdId);
        assertThat(money(finalBill.get("totalAmount"))).isEqualByComparingTo("4000");
        assertThat(money(finalBill.get("paidAmount"))).isEqualByComparingTo("4000");
        assertThat(money(finalBill.get("balance"))).isEqualByComparingTo("0");
        assertThat((List<Map<String, Object>>) finalBill.get("items"))
                .extracting(m -> String.valueOf(m.get("description")))
                .containsExactlyInAnyOrder("Bed charges", "Procedure", "Dressing");
        assertThat(billingPaymentRepository.findByBillingId(billId))
                .as("two collections, in the order they were taken").hasSize(2);
        assertReconciles(billId, new BigDecimal("4000"), new BigDecimal("4000"), "CLOSED");
    }

    /**
     * Discharge must not release a patient who still owes money -- and a refused discharge must
     * leave the admission and its bill exactly as they were.
     */
    @Test
    void dischargeIsRefusedWhileABalanceIsOutstanding() {
        String admitBody = "{\"opdId\":" + opdId + ",\"wardId\":" + wardId + ",\"bedId\":" + bedId
                + ",\"admissionType\":\"ELECTIVE\",\"primaryDiagnosis\":\"obs\"}";
        assertThat(post("/hospital/ipd/admit", admitBody).getStatusCode().value()).isEqualTo(200);
        long ipdId = ipdAdmissionRepository.findAll().stream()
                .filter(a -> a.getBedId() != null && a.getBedId().equals(bedId))
                .findFirst().orElseThrow().getId();
        long billId = ((Number) ipdBill(ipdId).get("billingId")).longValue();

        assertThat(post("/hospital/billing/" + billId + "/pay",
                "{\"amount\":500.00,\"mode\":\"CASH\"}").getStatusCode().value()).isEqualTo(200);

        assertThat(post("/hospital/ipd/" + ipdId + "/plan-discharge",
                "{\"finalDiagnosis\":\"resolved\"}").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> refused = post("/hospital/ipd/" + ipdId + "/confirm-discharge", "{}");
        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(refused.getBody()).contains("Outstanding balance");

        assertThat(ipdAdmissionRepository.findById(ipdId).orElseThrow().getStatus())
                .as("the admission stays planned, not discharged").isEqualTo("DISCHARGE_PLANNED");
        assertReconciles(billId, BED_PRICE, new BigDecimal("500"), "PARTIAL");
    }

    /**
     * Replacing a bill's line items is all-or-nothing.
     *
     * <p>The endpoint empties the bill and rebuilds it. Rejecting one of the replacement rows
     * part-way through used to leave the bill stripped of the charges it already had and never
     * rebuilt -- money simply gone from the bill, with no record that it had been there.
     */
    @Test
    void aRejectedLineItemRewrite_leavesTheOriginalChargesIntact() {
        String admitBody = "{\"opdId\":" + opdId + ",\"wardId\":" + wardId + ",\"bedId\":" + bedId
                + ",\"admissionType\":\"ELECTIVE\",\"primaryDiagnosis\":\"obs\"}";
        assertThat(post("/hospital/ipd/admit", admitBody).getStatusCode().value()).isEqualTo(200);
        long ipdId = ipdAdmissionRepository.findAll().stream()
                .filter(a -> a.getBedId() != null && a.getBedId().equals(bedId))
                .findFirst().orElseThrow().getId();
        long billId = ((Number) ipdBill(ipdId).get("billingId")).longValue();
        assertReconciles(billId, BED_PRICE, BigDecimal.ZERO, "PENDING");

        // billing_items.description is 200 characters. A longer one is rejected by the database
        // on flush -- after the delete has already been issued.
        String tooLong = "X".repeat(300);
        ResponseEntity<String> rejected = put("/hospital/billing/" + billId + "/items",
                "[{\"name\":\"Procedure\",\"defaultAmount\":2000},"
                        + "{\"name\":\"" + tooLong + "\",\"defaultAmount\":500}]");
        assertThat(rejected.getStatusCode().is2xxSuccessful())
                .as("the rewrite must be reported as failed").isFalse();

        assertThat(billingItemRepository.findByBillingId(billId))
                .as("the bill still holds the charge it had before the failed rewrite")
                .extracting(it -> String.valueOf(it.getDescription()))
                .doesNotContain("Procedure")
                .isNotEmpty();
        assertReconciles(billId, BED_PRICE, BigDecimal.ZERO, "PENDING");
    }
}

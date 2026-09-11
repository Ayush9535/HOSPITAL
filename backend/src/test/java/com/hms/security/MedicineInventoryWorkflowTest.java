package com.hms.security;

import com.hms.entity.Bed;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.IpdAdmission;
import com.hms.entity.IpdBedHistory;
import com.hms.entity.MedicineStockBatch;
import com.hms.entity.NurseProfile;
import com.hms.entity.Patient;
import com.hms.entity.PatientNurseAssignment;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.IpdBedHistoryRepository;
import com.hms.repository.MedicationAdministrationRepository;
import com.hms.repository.MedicineRepository;
import com.hms.repository.MedicineStockBatchRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.PrescriptionRepository;
import com.hms.repository.StockMovementRepository;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The journey the whole checkpoint exists for, end to end and persisted:
 * admin receives stock -> doctor prescribes on an IPD case -> nurse opens the medication chart ->
 * pharmacy dispenses -> nurse administers.
 *
 * <p>Two things are being held apart here, and the tests are written to keep them apart. A
 * prescription is a clinical instruction and appears on the chart whether or not the facility can
 * account for the stock behind it. Stock leaves the shelf exactly once, at dispense — ordering
 * does not take it and administering does not take it again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MedicineInventoryWorkflowTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES =
            List.of("OPD", "IPD", "NURSING", "PHARMACY", "BILLING", "MEDICAL_INVENTORY");

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitals;
    @Autowired UserRepository users;
    @Autowired WardRepository wards;
    @Autowired BedRepository beds;
    @Autowired PatientRepository patients;
    @Autowired DoctorRepository doctors;
    @Autowired NurseProfileRepository nurses;
    @Autowired IpdAdmissionRepository admissions;
    @Autowired IpdBedHistoryRepository bedHistory;
    @Autowired PatientNurseAssignmentRepository nurseAssignments;
    @Autowired PrescriptionRepository prescriptions;
    @Autowired MedicineRepository medicines;
    @Autowired MedicineStockBatchRepository batches;
    @Autowired StockMovementRepository movements;
    @Autowired MedicationAdministrationRepository mars;
    @Autowired com.hms.repository.HospitalSettingRepository hospitalSettings;
    @Autowired com.hms.repository.HospitalInventoryRepository hospitalInventory;
    @Autowired com.hms.repository.MedicalRecordRepository medicalRecords;

    private Hospital hospitalA;
    private String adminA, nurseTokenA, doctorTokenA, adminB;
    private IpdAdmission admissionA;

    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }

    // ------------------------------------------------------------------ fixture

    private Hospital tenant(String label, String type) {
        Hospital h = new Hospital();
        h.setName("Inv " + label);
        h.setCustomId("INV-" + uniq());
        h.setIsActive(true);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        h.setType(com.hms.entity.HospitalType.valueOf(type));
        h = hospitals.save(h);
        // Separate Nurse Login ON: the logged-in nurse is the performer, so the tests do not have
        // to nominate one on every administration.
        com.hms.entity.HospitalSetting setting = new com.hms.entity.HospitalSetting();
        setting.setHospital(h);
        setting.setSeparateNurseLogin(true);
        hospitalSettings.save(setting);
        return h;
    }

    private String tokenFor(Hospital h, String role, String type) {
        User u = new User();
        u.setEmail(role.toLowerCase() + "." + uniq() + "@inv.test");
        u.setPassword("{noop}fixture");
        u.setName("User " + role);
        u.setRole(role);
        u.setHospitalId(h.getId());
        u.setIsActive(true);
        u.setTokenVersion(0);
        u = users.save(u);
        return jwtUtil.generateToken(u.getId(), u.getEmail(), u.getRole(), h.getId(),
                MODULES, null, type, null, u.getTokenVersion());
    }

    @BeforeEach
    void setUp() {
        hospitalA = tenant("A", "HOSPITAL");
        Hospital hospitalB = tenant("B", "HOSPITAL");
        adminA = tokenFor(hospitalA, "HOSPITAL_ADMIN", "HOSPITAL");
        adminB = tokenFor(hospitalB, "HOSPITAL_ADMIN", "HOSPITAL");
        doctorTokenA = tokenFor(hospitalA, "DOCTOR", "HOSPITAL");

        Ward ward = new Ward();
        ward.setWardName("Ward A");
        ward.setHospitalId(hospitalA.getId());
        ward.setBedPrice(new BigDecimal("1000"));
        ward.setTotalBeds(1);
        ward = wards.save(ward);

        // A staff nurse with a login, on the ward the patient is admitted to.
        User nurseUser = new User();
        nurseUser.setEmail("nurse." + uniq() + "@inv.test");
        nurseUser.setPassword("{noop}fixture");
        nurseUser.setName("Nurse A");
        nurseUser.setRole("NURSE");
        nurseUser.setHospitalId(hospitalA.getId());
        nurseUser.setIsActive(true);
        nurseUser.setTokenVersion(0);
        nurseUser = users.save(nurseUser);
        nurseTokenA = jwtUtil.generateToken(nurseUser.getId(), nurseUser.getEmail(), "NURSE",
                hospitalA.getId(), MODULES, null, "HOSPITAL", null, 0);

        NurseProfile nurse = new NurseProfile();
        nurse.setHospitalId(hospitalA.getId());
        nurse.setUserId(nurseUser.getId());
        nurse.setName("Nurse A");
        nurse.setEmail(nurseUser.getEmail());
        nurse.setWardId(ward.getWardId());
        nurse.setIsIncharge(false);
        nurse.setIsActive(true);
        nurse.setOnShift(true);
        nurses.save(nurse);

        Doctor doctor = new Doctor();
        doctor.setHospitalId(hospitalA.getId());
        doctor.setName("Dr A");
        doctor.setEmail("doctor." + uniq() + "@inv.test");
        doctor.setPublicId("dpub-" + uniq());
        doctor.setPhone("9800000002");
        doctor.setSpecialization("Gen");
        doctor.setIsActive(true);
        doctor = doctors.save(doctor);

        Patient patient = new Patient();
        patient.setHospitalId(hospitalA.getId());
        patient.setName("Pat A");
        patient.setPublicId("ppub-" + uniq());
        patient.setGender("MALE");
        patient.setPhone("9900000002");
        patient.setIsActive(true);
        patient.setDateOfBirth(LocalDate.of(1980, 1, 1));
        patient = patients.save(patient);

        Bed bed = new Bed();
        bed.setHospitalId(hospitalA.getId());
        bed.setWardId(ward.getWardId());
        bed.setBedCode("BED-" + uniq());
        bed.setStatus("occupied");
        bed = beds.save(bed);

        IpdAdmission adm = new IpdAdmission();
        adm.setHospitalId(hospitalA.getId());
        adm.setPatientId(patient.getId());
        adm.setDoctorId(doctor.getId());
        adm.setWardId(ward.getWardId());
        adm.setBedId(bed.getBedId());
        adm.setIpdNumber("IPD-" + uniq());
        adm.setAdmissionType("ELECTIVE");
        adm.setStatus("ADMITTED");
        adm.setAdmissionDatetime(LocalDateTime.now());
        admissionA = admissions.save(adm);

        bed.setCurrentIpdAdmissionId(admissionA.getId());
        beds.save(bed);

        IpdBedHistory hist = new IpdBedHistory();
        hist.setIpdAdmissionId(admissionA.getId());
        hist.setBedId(bed.getBedId());
        hist.setWardId(ward.getWardId());
        hist.setAssignedAt(LocalDateTime.now());
        bedHistory.save(hist);

        PatientNurseAssignment link = new PatientNurseAssignment();
        link.setHospitalId(hospitalA.getId());
        link.setIpdAdmissionId(admissionA.getId());
        link.setNurseUserId(nurseUser.getId());
        link.setAssignedByUserId(nurseUser.getId());
        link.setPatientId(patient.getId());
        link.setIsActive(true);
        link.setAssignedAt(LocalDateTime.now());
        nurseAssignments.save(link);
    }

    // ------------------------------------------------------------------ helpers

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), String.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chart(String token, long admissionId) {
        ResponseEntity<List> res = rest.exchange(
                "/hospital/nurse/medication/admission/" + admissionId + "/chart",
                HttpMethod.GET, new HttpEntity<>(headers(token)), List.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody();
    }

    private Map<String, Object> row(List<Map<String, Object>> chart, String medicineName) {
        return chart.stream()
                .filter(r -> medicineName.equals(r.get("medicineName")))
                .findFirst().orElseThrow(() -> new AssertionError(
                        medicineName + " is missing from the medication chart: " + chart));
    }

    private String purchaseBody(String name, int qty, LocalDate expiry, String batchNumber) {
        return "{\"name\":\"" + name + "\",\"quantity\":" + qty
                + ",\"unitPrice\":5.0,\"expiryDate\":\"" + expiry + "\""
                + (batchNumber == null ? "" : ",\"batchNumber\":\"" + batchNumber + "\"")
                + ",\"type\":\"Tablet\",\"purchaseDate\":\"" + LocalDateTime.now() + "\"}";
    }

    private Long medicineIdByName(String name) {
        return medicines.findByHospitalId(hospitalA.getId()).stream()
                .filter(m -> name.equalsIgnoreCase(m.getName()))
                .findFirst().orElseThrow().getId();
    }

    private int usableStock(Long medicineId) {
        return batches.availableQuantity(hospitalA.getId(), medicineId, LocalDate.now());
    }

    // ------------------------------------------------------------------ tests

    /**
     * Receiving stock twice with different expiries must produce two lots, not one merged row
     * carrying the later date.
     *
     * <p>This is the destructive merge the batch model replaces: the old code added the quantity
     * to the existing medicine row and overwrote its expiry with the new delivery's, so the
     * earlier stock silently inherited a date it never had and stayed dispensable past its own.
     */
    @Test
    void twoDeliveriesWithDifferentExpiries_becomeTwoLots_andNeitherInheritsTheOthersDate() {
        String name = "Paracetamol " + uniq();
        LocalDate soon = LocalDate.now().plusMonths(1);
        LocalDate later = LocalDate.now().plusMonths(9);

        assertThat(post("/hospital/medicines/purchases", adminA, purchaseBody(name, 100, later, "LOT-LATER"))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(post("/hospital/medicines/purchases", adminA, purchaseBody(name, 50, soon, "LOT-SOON"))
                .getStatusCode().value()).isEqualTo(200);

        Long medicineId = medicineIdByName(name);
        List<MedicineStockBatch> lots =
                batches.findByHospitalIdAndMedicineIdOrderByExpiryDateAsc(hospitalA.getId(), medicineId);

        assertThat(lots).hasSize(2);
        assertThat(lots).extracting(MedicineStockBatch::getExpiryDate).containsExactly(soon, later);
        assertThat(lots).extracting(MedicineStockBatch::getCurrentQuantity).containsExactly(50, 100);
        assertThat(usableStock(medicineId)).as("both lots count as stock").isEqualTo(150);

        // Every receipt is on the ledger, and the ledger explains each lot.
        for (MedicineStockBatch lot : lots) {
            assertThat(movements.reconciledBatchQuantity(hospitalA.getId(), lot.getId()))
                    .isEqualTo(lot.getCurrentQuantity());
        }
    }

    /** A delivery with no supplier batch number is filed under its expiry, never a made-up code. */
    @Test
    void aDeliveryWithoutABatchNumberIsFiledUnderItsExpiry() {
        String name = "Amoxicillin " + uniq();
        LocalDate expiry = LocalDate.now().plusMonths(4);

        assertThat(post("/hospital/medicines/purchases", adminA, purchaseBody(name, 30, expiry, null))
                .getStatusCode().value()).isEqualTo(200);
        // The same lot arriving again tops it up rather than fragmenting into a second row.
        assertThat(post("/hospital/medicines/purchases", adminA, purchaseBody(name, 20, expiry, null))
                .getStatusCode().value()).isEqualTo(200);

        Long medicineId = medicineIdByName(name);
        List<MedicineStockBatch> lots =
                batches.findByHospitalIdAndMedicineIdOrderByExpiryDateAsc(hospitalA.getId(), medicineId);
        assertThat(lots).hasSize(1);
        assertThat(lots.get(0).getBatchNumber()).isEqualTo("EXP-" + expiry);
        assertThat(usableStock(medicineId)).isEqualTo(50);
    }

    /**
     * The reported user-visible bug: the nurse must see what the doctor prescribed, whether or not
     * the order can be reconciled with inventory.
     */
    @Test
    void theNurseSeesBothLinkedAndUnlinkedPrescriptions_withHonestStockStates() {
        String stocked = "Ceftriaxone " + uniq();
        LocalDate soon = LocalDate.now().plusMonths(2);
        LocalDate later = LocalDate.now().plusMonths(8);
        post("/hospital/medicines/purchases", adminA, purchaseBody(stocked, 40, later, "L2"));
        post("/hospital/medicines/purchases", adminA, purchaseBody(stocked, 10, soon, "L1"));
        Long medicineId = medicineIdByName(stocked);

        // Linked: the doctor picked the row out of inventory.
        assertThat(post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineId\":" + medicineId + ",\"dose\":\"1g\",\"frequency\":\"1-0-1\","
                        + "\"durationDays\":3,\"startDate\":\"" + LocalDate.now() + "\",\"type\":\"INJECTION\",\"route\":\"IV\"}")
                .getStatusCode().value()).isEqualTo(200);

        // Unlinked: typed by hand, and the facility does not stock it. Still a valid order.
        String freeText = "Compounded syrup " + uniq();
        assertThat(post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineName\":\"" + freeText + "\",\"dose\":\"5ml\",\"frequency\":\"1-1-1\","
                        + "\"durationDays\":2,\"startDate\":\"" + LocalDate.now() + "\",\"type\":\"TABLET\",\"route\":\"ORAL\"}")
                .getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> chart = chart(nurseTokenA, admissionA.getId());

        Map<String, Object> linked = row(chart, stocked);
        assertThat(linked.get("inventoryStatus")).isEqualTo("LINKED_AVAILABLE");
        assertThat(((Number) linked.get("availableQuantity")).intValue()).isEqualTo(50);
        assertThat(linked.get("earliestExpiry"))
                .as("FEFO would reach for the lot expiring first").isEqualTo(soon.toString());

        Map<String, Object> unlinked = row(chart, freeText);
        assertThat(unlinked.get("inventoryStatus")).isEqualTo("UNLINKED");
        assertThat(unlinked.get("availableQuantity"))
                .as("unknown stock is absent, never reported as zero").isNull();
        assertThat(unlinked.get("medicineId")).isNull();
    }

    /** A linked medicine whose every lot is gone reads as no stock — which is not the same as unlinked. */
    @Test
    void aLinkedOrderWithNothingUsableLeftReadsAsNoStock() {
        String name = "Metformin " + uniq();
        post("/hospital/medicines/purchases", adminA, purchaseBody(name, 5, LocalDate.now().plusMonths(3), "M1"));
        Long medicineId = medicineIdByName(name);

        assertThat(post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineId\":" + medicineId + ",\"dose\":\"500mg\",\"frequency\":\"1-0-1\","
                        + "\"durationDays\":2,\"startDate\":\"" + LocalDate.now() + "\",\"type\":\"TABLET\",\"route\":\"ORAL\"}")
                .getStatusCode().value()).isEqualTo(200);
        Long prescriptionId = prescriptions.findByIpdAdmissionIdAndStatus(admissionA.getId(), "ACTIVE")
                .stream().filter(p -> medicineId.equals(p.getMedicineId()))
                .findFirst().orElseThrow().getId();

        // Empty the shelf through the real dispense path.
        assertThat(post("/hospital/pharmacy/dispense/" + prescriptionId, adminA,
                "{\"quantity\":5}").getStatusCode().value()).isEqualTo(200);

        Map<String, Object> row = row(chart(nurseTokenA, admissionA.getId()), name);
        assertThat(row.get("inventoryStatus")).isEqualTo("LINKED_NO_STOCK");
        assertThat(((Number) row.get("availableQuantity")).intValue()).isZero();
        assertThat(row.get("earliestExpiry")).isNull();
    }

    /**
     * Dispense removes what the pharmacist says was handed over.
     *
     * <p>The endpoint used to remove exactly one unit whatever the course was, against whichever
     * inventory row matched the prescription's text first.
     */
    @Test
    void dispenseRemovesTheRequestedQuantityAndTakesTheEarliestExpiryFirst() {
        String name = "Azithromycin " + uniq();
        LocalDate soon = LocalDate.now().plusMonths(1);
        LocalDate later = LocalDate.now().plusMonths(10);
        post("/hospital/medicines/purchases", adminA, purchaseBody(name, 10, soon, "A-SOON"));
        post("/hospital/medicines/purchases", adminA, purchaseBody(name, 40, later, "A-LATER"));
        Long medicineId = medicineIdByName(name);

        post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineId\":" + medicineId + ",\"dose\":\"500mg\",\"frequency\":\"1-0-1\","
                        + "\"durationDays\":6,\"startDate\":\"" + LocalDate.now() + "\",\"type\":\"TABLET\",\"route\":\"ORAL\"}");
        Long prescriptionId = prescriptions.findByIpdAdmissionIdAndStatus(admissionA.getId(), "ACTIVE")
                .stream().filter(p -> medicineId.equals(p.getMedicineId()))
                .findFirst().orElseThrow().getId();

        ResponseEntity<String> dispensed = post("/hospital/pharmacy/dispense/" + prescriptionId, adminA,
                "{\"quantity\":12,\"idempotencyKey\":\"disp-" + uniq() + "\"}");
        assertThat(dispensed.getStatusCode().value()).as("%s", dispensed.getBody()).isEqualTo(200);
        assertThat(dispensed.getBody()).contains("\"quantityDispensed\":12");

        assertThat(usableStock(medicineId)).as("12 units left the shelf, not 1").isEqualTo(38);

        List<MedicineStockBatch> lots =
                batches.findByHospitalIdAndMedicineIdOrderByExpiryDateAsc(hospitalA.getId(), medicineId);
        assertThat(lots.get(0).getCurrentQuantity()).as("the soon-expiring lot is emptied first").isZero();
        assertThat(lots.get(1).getCurrentQuantity()).isEqualTo(38);
    }

    /** A resent dispense — a retry, a double submit — posts stock once. */
    @Test
    void replayingADispenseDoesNotTakeStockTwice() {
        String name = "Pantoprazole " + uniq();
        post("/hospital/medicines/purchases", adminA, purchaseBody(name, 30, LocalDate.now().plusMonths(5), "P1"));
        Long medicineId = medicineIdByName(name);

        post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineId\":" + medicineId + ",\"dose\":\"40mg\",\"frequency\":\"1-0-0\","
                        + "\"durationDays\":5,\"startDate\":\"" + LocalDate.now() + "\",\"type\":\"TABLET\",\"route\":\"ORAL\"}");
        Long prescriptionId = prescriptions.findByIpdAdmissionIdAndStatus(admissionA.getId(), "ACTIVE")
                .stream().filter(p -> medicineId.equals(p.getMedicineId()))
                .findFirst().orElseThrow().getId();

        String key = "retry-" + uniq();
        assertThat(post("/hospital/pharmacy/dispense/" + prescriptionId, adminA,
                "{\"quantity\":5,\"idempotencyKey\":\"" + key + "\"}").getStatusCode().value()).isEqualTo(200);
        post("/hospital/pharmacy/dispense/" + prescriptionId, adminA,
                "{\"quantity\":5,\"idempotencyKey\":\"" + key + "\"}");

        assertThat(usableStock(medicineId)).as("one act of dispensing, one deduction").isEqualTo(25);
    }

    /**
     * Administering is a clinical record, not a second withdrawal from the shelf.
     *
     * <p>This is what keeps ORDER / DISPENSE / ADMINISTER from each taking the same stock.
     */
    @Test
    void administeringDoesNotDecrementStockAgainAfterDispense() {
        String name = "Ondansetron " + uniq();
        post("/hospital/medicines/purchases", adminA, purchaseBody(name, 20, LocalDate.now().plusMonths(6), "O1"));
        Long medicineId = medicineIdByName(name);

        post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineId\":" + medicineId + ",\"dose\":\"4mg\",\"frequency\":\"1-1-1\","
                        + "\"durationDays\":2,\"startDate\":\"" + LocalDate.now() + "\",\"type\":\"INJECTION\",\"route\":\"IV\"}");
        Long prescriptionId = prescriptions.findByIpdAdmissionIdAndStatus(admissionA.getId(), "ACTIVE")
                .stream().filter(p -> medicineId.equals(p.getMedicineId()))
                .findFirst().orElseThrow().getId();

        post("/hospital/pharmacy/dispense/" + prescriptionId, adminA, "{\"quantity\":6}");
        int afterDispense = usableStock(medicineId);
        assertThat(afterDispense).isEqualTo(14);
        int movementsAfterDispense = movements
                .findByHospitalIdAndInventoryDomainAndItemIdOrderByIdAsc(
                        hospitalA.getId(), com.hms.entity.StockMovement.DOMAIN_MEDICINE, medicineId).size();

        String marBody = "{\"ipdAdmissionId\":" + admissionA.getId()
                + ",\"prescriptionId\":" + prescriptionId
                + ",\"status\":\"GIVEN\",\"administeredTime\":\"" + LocalDateTime.now() + "\"}";
        assertThat(post("/hospital/nurse/medication", nurseTokenA, marBody).getStatusCode().value()).isEqualTo(200);

        assertThat(usableStock(medicineId))
                .as("administering records care; it does not take stock").isEqualTo(afterDispense);
        assertThat(prescriptions.findById(prescriptionId).orElseThrow().getStatus())
                .as("dispensing hands over stock; it does not end the course").isEqualTo("ACTIVE");
        assertThat(movements.findByHospitalIdAndInventoryDomainAndItemIdOrderByIdAsc(
                hospitalA.getId(), com.hms.entity.StockMovement.DOMAIN_MEDICINE, medicineId))
                .as("and posts no stock movement").hasSize(movementsAfterDispense);

        // ...and the clinical record itself is intact.
        assertThat(mars.findByPrescriptionIdAndIsActiveTrueOrderByCreatedAtDesc(prescriptionId))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getStatus()).isEqualTo("GIVEN");
                    assertThat(m.getAdministeredTime()).isNotNull();
                    assertThat(m.getNurseUserId()).isNotNull();
                });
    }

    /** A double-clicked "Given" is one dose, and the second click says so. */
    @Test
    void thesameAdministrationSubmittedTwiceIsRefused() {
        String name = "Ranitidine " + uniq();
        post("/hospital/medicines/purchases", adminA, purchaseBody(name, 10, LocalDate.now().plusMonths(6), "R1"));
        Long medicineId = medicineIdByName(name);
        post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineId\":" + medicineId + ",\"dose\":\"150mg\",\"frequency\":\"1-0-1\","
                        + "\"durationDays\":2,\"startDate\":\"" + LocalDate.now() + "\",\"type\":\"TABLET\",\"route\":\"ORAL\"}");
        Long prescriptionId = prescriptions.findByIpdAdmissionIdAndStatus(admissionA.getId(), "ACTIVE")
                .stream().filter(p -> medicineId.equals(p.getMedicineId()))
                .findFirst().orElseThrow().getId();

        String marBody = "{\"ipdAdmissionId\":" + admissionA.getId()
                + ",\"prescriptionId\":" + prescriptionId
                + ",\"status\":\"GIVEN\",\"administeredTime\":\"" + LocalDateTime.now() + "\"}";

        assertThat(post("/hospital/nurse/medication", nurseTokenA, marBody).getStatusCode().value()).isEqualTo(200);
        assertThat(post("/hospital/nurse/medication", nurseTokenA, marBody).getStatusCode().value()).isEqualTo(409);

        assertThat(mars.findByPrescriptionIdAndIsActiveTrueOrderByCreatedAtDesc(prescriptionId))
                .as("one dose recorded, not two").hasSize(1);
    }

    /** Another facility cannot dispense against this one's prescription, or move its stock. */
    @Test
    void aForeignFacilityCannotDispenseThisFacilitysPrescription() {
        String name = "Insulin " + uniq();
        post("/hospital/medicines/purchases", adminA, purchaseBody(name, 25, LocalDate.now().plusMonths(7), "I1"));
        Long medicineId = medicineIdByName(name);
        post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineId\":" + medicineId + ",\"dose\":\"10u\",\"frequency\":\"1-0-1\","
                        + "\"durationDays\":3,\"startDate\":\"" + LocalDate.now() + "\",\"type\":\"INJECTION\",\"route\":\"IM\"}");
        Long prescriptionId = prescriptions.findByIpdAdmissionIdAndStatus(admissionA.getId(), "ACTIVE")
                .stream().filter(p -> medicineId.equals(p.getMedicineId()))
                .findFirst().orElseThrow().getId();

        ResponseEntity<String> foreign = post("/hospital/pharmacy/dispense/" + prescriptionId, adminB,
                "{\"quantity\":5}");
        assertThat(foreign.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(usableStock(medicineId)).as("no stock moved").isEqualTo(25);
    }

    /** A doctor cannot attach another facility's medicine to an order here. */
    @Test
    void aPrescriptionCannotLinkToAnotherFacilitysMedicine() {
        Hospital other = tenant("C", "HOSPITAL");
        String otherAdmin = tokenFor(other, "HOSPITAL_ADMIN", "HOSPITAL");
        String name = "Foreign drug " + uniq();
        assertThat(post("/hospital/medicines/purchases", otherAdmin,
                purchaseBody(name, 10, LocalDate.now().plusMonths(3), "F1")).getStatusCode().value()).isEqualTo(200);
        Long foreignMedicineId = medicines.findByHospitalId(other.getId()).stream()
                .filter(m -> name.equalsIgnoreCase(m.getName())).findFirst().orElseThrow().getId();

        ResponseEntity<String> res = post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineId\":" + foreignMedicineId + ",\"dose\":\"1\",\"frequency\":\"1-0-0\","
                        + "\"durationDays\":1,\"startDate\":\"" + LocalDate.now() + "\",\"type\":\"TABLET\",\"route\":\"ORAL\"}");

        assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(batches.availableQuantity(other.getId(), foreignMedicineId, LocalDate.now()))
                .as("the other facility's stock is untouched").isEqualTo(10);
    }

    /** The same stock rules apply to a clinic tenant, on the /clinic/** aliases. */
    @Test
    void aClinicTenantReceivesAndSeparatesItsOwnBatches() {
        Hospital clinic = tenant("Clinic", "CLINIC");
        String clinicAdmin = tokenFor(clinic, "HOSPITAL_ADMIN", "CLINIC");
        String name = "Clinic drug " + uniq();
        LocalDate soon = LocalDate.now().plusMonths(1);
        LocalDate later = LocalDate.now().plusMonths(11);

        assertThat(post("/clinic/medicines/purchases", clinicAdmin, purchaseBody(name, 12, later, "C-LATER"))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(post("/clinic/medicines/purchases", clinicAdmin, purchaseBody(name, 8, soon, "C-SOON"))
                .getStatusCode().value()).isEqualTo(200);

        Long medicineId = medicines.findByHospitalId(clinic.getId()).stream()
                .filter(m -> name.equalsIgnoreCase(m.getName())).findFirst().orElseThrow().getId();

        assertThat(batches.findByHospitalIdAndMedicineIdOrderByExpiryDateAsc(clinic.getId(), medicineId))
                .extracting(MedicineStockBatch::getExpiryDate).containsExactly(soon, later);
        assertThat(batches.availableQuantity(clinic.getId(), medicineId, LocalDate.now())).isEqualTo(20);

        // ...and this hospital's stock is not visible to it, nor its own to the hospital.
        assertThat(batches.availableQuantity(hospitalA.getId(), medicineId, LocalDate.now())).isZero();
    }

    /** Asking for more than exists changes nothing at all — no partial take, no ledger row. */
    @Test
    void anOverdrawnDispenseIsRefusedWhole() {
        String name = "Diclofenac " + uniq();
        post("/hospital/medicines/purchases", adminA, purchaseBody(name, 4, LocalDate.now().plusMonths(3), "D1"));
        Long medicineId = medicineIdByName(name);
        post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineId\":" + medicineId + ",\"dose\":\"50mg\",\"frequency\":\"1-0-1\","
                        + "\"durationDays\":5,\"startDate\":\"" + LocalDate.now() + "\",\"type\":\"TABLET\",\"route\":\"ORAL\"}");
        Long prescriptionId = prescriptions.findByIpdAdmissionIdAndStatus(admissionA.getId(), "ACTIVE")
                .stream().filter(p -> medicineId.equals(p.getMedicineId()))
                .findFirst().orElseThrow().getId();

        int before = movements.findByHospitalIdAndInventoryDomainAndItemIdOrderByIdAsc(
                hospitalA.getId(), com.hms.entity.StockMovement.DOMAIN_MEDICINE, medicineId).size();

        ResponseEntity<String> res = post("/hospital/pharmacy/dispense/" + prescriptionId, adminA,
                "{\"quantity\":10}");

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(usableStock(medicineId)).isEqualTo(4);
        assertThat(movements.findByHospitalIdAndInventoryDomainAndItemIdOrderByIdAsc(
                hospitalA.getId(), com.hms.entity.StockMovement.DOMAIN_MEDICINE, medicineId))
                .as("a refused dispense is not a stock event").hasSize(before);
        assertThat(prescriptions.findById(prescriptionId).orElseThrow().getStatus())
                .as("the clinical order is untouched by a failed stock event").isEqualTo("ACTIVE");
    }

    /** An order with no inventory link cannot be dispensed blind — the medicine must be named. */
    @Test
    void dispensingAnUnlinkedOrderRequiresChoosingTheMedicine() {
        String freeText = "Handwritten mixture " + uniq();
        post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineName\":\"" + freeText + "\",\"dose\":\"5ml\",\"frequency\":\"1-0-1\","
                        + "\"durationDays\":2,\"startDate\":\"" + LocalDate.now() + "\",\"type\":\"TABLET\",\"route\":\"ORAL\"}");
        Long prescriptionId = prescriptions.findByIpdAdmissionIdAndStatus(admissionA.getId(), "ACTIVE")
                .stream().filter(p -> freeText.equals(p.getMedicineName()))
                .findFirst().orElseThrow().getId();

        ResponseEntity<String> blind = post("/hospital/pharmacy/dispense/" + prescriptionId, adminA,
                "{\"quantity\":3}");
        assertThat(blind.getStatusCode().value()).isEqualTo(400);
        assertThat(blind.getBody()).contains("not linked");

        // Naming it reconciles the order, and the link is kept so nobody has to decide twice.
        String stocked = "Reconciled drug " + uniq();
        post("/hospital/medicines/purchases", adminA, purchaseBody(stocked, 10, LocalDate.now().plusMonths(3), "RC1"));
        Long medicineId = medicineIdByName(stocked);

        assertThat(post("/hospital/pharmacy/dispense/" + prescriptionId, adminA,
                "{\"quantity\":3,\"medicineId\":" + medicineId + "}").getStatusCode().value()).isEqualTo(200);
        assertThat(usableStock(medicineId)).isEqualTo(7);
        assertThat(prescriptions.findById(prescriptionId).orElseThrow().getMedicineId()).isEqualTo(medicineId);
    }

    // ---------------------------------------------------- INV-6: the dispensing workflow

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pendingQueue(String token) {
        ResponseEntity<List> res = rest.exchange("/hospital/pharmacy/prescriptions/pending",
                HttpMethod.GET, new HttpEntity<>(headers(token)), List.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dispensableMedicines(String token, String prefix, String query) {
        ResponseEntity<List> res = rest.exchange(
                prefix + "/pharmacy/dispense/medicines" + (query == null ? "" : "?query=" + query),
                HttpMethod.GET, new HttpEntity<>(headers(token)), List.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody();
    }

    private Map<String, Object> queueRow(List<Map<String, Object>> queue, String medicineName) {
        return queue.stream().filter(r -> medicineName.equals(r.get("medicineName")))
                .findFirst().orElseThrow(() -> new AssertionError(
                        medicineName + " is missing from the dispensing queue: " + queue));
    }

    /**
     * The whole reported journey, end to end and persisted: admin receives a batch, doctor
     * prescribes for an IPD patient, the order reaches both the nurse's chart and the dispensing
     * queue, the dispensing user reconciles it and issues five units, stock falls by exactly five,
     * the ledger explains it, and the nurse then administers without stock moving again.
     */
    @Test
    void theFullDispensingJourney_movesStockExactlyOnce() {
        String name = "Cefixime " + uniq();
        LocalDate expiry = LocalDate.now().plusMonths(8);
        assertThat(post("/hospital/medicines/purchases", adminA, purchaseBody(name, 40, expiry, "BATCH-A"))
                .getStatusCode().value()).isEqualTo(200);
        Long medicineId = medicineIdByName(name);

        // The doctor writes the order as free text -- no inventory row attached.
        assertThat(post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineName\":\"" + name + "\",\"dose\":\"200mg\",\"frequency\":\"1-0-1\","
                        + "\"durationDays\":5,\"startDate\":\"" + LocalDate.now() + "\","
                        + "\"type\":\"TABLET\",\"route\":\"ORAL\"}")
                .getStatusCode().value()).isEqualTo(200);
        Long prescriptionId = prescriptions.findByIpdAdmissionIdAndStatus(admissionA.getId(), "ACTIVE")
                .stream().filter(p -> name.equals(p.getMedicineName()))
                .findFirst().orElseThrow().getId();

        // It is on the nurse's chart, and on the dispensing queue, as UNLINKED in both.
        assertThat(row(chart(nurseTokenA, admissionA.getId()), name).get("inventoryStatus"))
                .isEqualTo("UNLINKED");
        Map<String, Object> queued = queueRow(pendingQueue(adminA), name);
        assertThat(queued.get("inventoryStatus")).isEqualTo("UNLINKED");
        assertThat(queued.get("medicineId")).isNull();
        assertThat(((Number) queued.get("quantityDispensed")).intValue()).isZero();

        // The dispensing user picks the medicine from this facility's own list and issues five.
        List<Map<String, Object>> options = dispensableMedicines(adminA, "/hospital", null);
        Map<String, Object> option = options.stream()
                .filter(o -> name.equals(o.get("name"))).findFirst().orElseThrow();
        assertThat(((Number) option.get("availableQuantity")).intValue()).isEqualTo(40);
        assertThat(option.get("earliestExpiry")).isEqualTo(expiry.toString());

        assertThat(post("/hospital/pharmacy/dispense/" + prescriptionId, adminA,
                "{\"quantity\":5,\"medicineId\":" + option.get("medicineId")
                        + ",\"idempotencyKey\":\"j-" + uniq() + "\"}")
                .getStatusCode().value()).isEqualTo(200);

        assertThat(usableStock(medicineId)).as("exactly five units left the shelf").isEqualTo(35);
        assertThat(movements.dispensedForPrescription(hospitalA.getId(), prescriptionId)).isEqualTo(5);

        // The ledger reconciles to what is physically there.
        MedicineStockBatch lot = batches
                .findByHospitalIdAndMedicineIdOrderByExpiryDateAsc(hospitalA.getId(), medicineId).get(0);
        assertThat(movements.reconciledBatchQuantity(hospitalA.getId(), lot.getId()))
                .isEqualTo(lot.getCurrentQuantity());

        // Reconciling stuck: the queue and the chart now both read LINKED, with the issue visible.
        Map<String, Object> afterIssue = queueRow(pendingQueue(adminA), name);
        assertThat(afterIssue.get("inventoryStatus")).isEqualTo("LINKED_AVAILABLE");
        assertThat(((Number) afterIssue.get("availableQuantity")).intValue()).isEqualTo(35);
        assertThat(((Number) afterIssue.get("quantityDispensed")).intValue()).isEqualTo(5);

        Map<String, Object> chartRow = row(chart(nurseTokenA, admissionA.getId()), name);
        assertThat(chartRow.get("inventoryStatus")).isEqualTo("LINKED_AVAILABLE");
        assertThat(((Number) chartRow.get("quantityDispensed")).intValue()).isEqualTo(5);

        // The nurse gives the dose. Nothing moves.
        assertThat(post("/hospital/nurse/medication", nurseTokenA,
                "{\"ipdAdmissionId\":" + admissionA.getId() + ",\"prescriptionId\":" + prescriptionId
                        + ",\"status\":\"GIVEN\",\"administeredTime\":\"" + LocalDateTime.now() + "\"}")
                .getStatusCode().value()).isEqualTo(200);
        assertThat(usableStock(medicineId)).as("administering is not a second withdrawal").isEqualTo(35);
        assertThat(movements.dispensedForPrescription(hospitalA.getId(), prescriptionId)).isEqualTo(5);
    }

    /** Stock that has only expired lots is offered as zero, and cannot be issued. */
    @Test
    void expiredOnlyStockIsOfferedAsUnavailableAndCannotBeDispensed() {
        String name = "Expired drug " + uniq();
        post("/hospital/medicines/purchases", adminA, purchaseBody(name, 10, LocalDate.now().plusMonths(2), "E1"));
        Long medicineId = medicineIdByName(name);

        // Age the lot past its expiry, as time would.
        MedicineStockBatch lot = batches
                .findByHospitalIdAndMedicineIdOrderByExpiryDateAsc(hospitalA.getId(), medicineId).get(0);
        lot.setExpiryDate(LocalDate.now().minusDays(1));
        batches.save(lot);

        Map<String, Object> option = dispensableMedicines(adminA, "/hospital", null).stream()
                .filter(o -> name.equals(o.get("name"))).findFirst().orElseThrow();
        assertThat(((Number) option.get("availableQuantity")).intValue())
                .as("expired units are not stock").isZero();
        assertThat(option.get("earliestExpiry")).isNull();

        post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineId\":" + medicineId + ",\"dose\":\"1\",\"frequency\":\"1-0-0\","
                        + "\"durationDays\":1,\"startDate\":\"" + LocalDate.now() + "\","
                        + "\"type\":\"TABLET\",\"route\":\"ORAL\"}");
        Long prescriptionId = prescriptions.findByIpdAdmissionIdAndStatus(admissionA.getId(), "ACTIVE")
                .stream().filter(p -> medicineId.equals(p.getMedicineId()))
                .findFirst().orElseThrow().getId();

        assertThat(post("/hospital/pharmacy/dispense/" + prescriptionId, adminA, "{\"quantity\":1}")
                .getStatusCode().value()).isEqualTo(409);
        assertThat(batches.findById(lot.getId()).orElseThrow().getCurrentQuantity())
                .as("the expired lot is not touched").isEqualTo(10);
    }

    /** The picker shows this facility's medicines and nobody else's. */
    @Test
    void theMedicinePickerIsScopedToTheCallersFacility() {
        String mine = "Mine " + uniq();
        post("/hospital/medicines/purchases", adminA, purchaseBody(mine, 5, LocalDate.now().plusMonths(3), "M1"));

        Hospital other = tenant("Picker", "HOSPITAL");
        String otherAdmin = tokenFor(other, "HOSPITAL_ADMIN", "HOSPITAL");
        String theirs = "Theirs " + uniq();
        post("/hospital/medicines/purchases", otherAdmin,
                purchaseBody(theirs, 5, LocalDate.now().plusMonths(3), "T1"));

        assertThat(dispensableMedicines(adminA, "/hospital", null))
                .extracting(o -> String.valueOf(o.get("name")))
                .contains(mine)
                .doesNotContain(theirs);
    }

    /**
     * General hospital inventory -- gloves, syringes, linen -- is not a dispensable medicine and
     * must never appear in the picker. It lives in its own table, and this pins that.
     */
    @Test
    void generalHospitalInventoryItemsNeverAppearInTheMedicinePicker() {
        com.hms.entity.HospitalInventory item = new com.hms.entity.HospitalInventory();
        item.setHospitalId(hospitalA.getId());
        item.setName("Surgical gloves " + uniq());
        item.setStockQuantity(500);
        item.setUnitPrice(2.0);
        item.setIsActive(true);
        hospitalInventory.save(item);

        assertThat(dispensableMedicines(adminA, "/hospital", null))
                .extracting(o -> String.valueOf(o.get("name")))
                .doesNotContain(item.getName());
    }

    /** Search narrows the picker; it never narrows it to one automatic answer. */
    @Test
    void searchingThePickerNarrowsButNeverAutoSelects() {
        String hit = "Searchable " + uniq();
        String miss = "Unrelated " + uniq();
        post("/hospital/medicines/purchases", adminA, purchaseBody(hit, 5, LocalDate.now().plusMonths(3), "S1"));
        post("/hospital/medicines/purchases", adminA, purchaseBody(miss, 5, LocalDate.now().plusMonths(3), "S2"));

        List<Map<String, Object>> found = dispensableMedicines(adminA, "/hospital", "Searchable");
        assertThat(found).extracting(o -> String.valueOf(o.get("name")))
                .contains(hit).doesNotContain(miss);
    }

    /** A doctor can read a prescription but must not be able to move stock with it. */
    @Test
    void aDoctorCannotDispense() {
        String name = "Doctor drug " + uniq();
        post("/hospital/medicines/purchases", adminA, purchaseBody(name, 20, LocalDate.now().plusMonths(4), "D9"));
        Long medicineId = medicineIdByName(name);
        post("/hospital/ipd/" + admissionA.getId() + "/prescriptions", doctorTokenA,
                "{\"medicineId\":" + medicineId + ",\"dose\":\"1\",\"frequency\":\"1-0-0\","
                        + "\"durationDays\":2,\"startDate\":\"" + LocalDate.now() + "\","
                        + "\"type\":\"TABLET\",\"route\":\"ORAL\"}");
        Long prescriptionId = prescriptions.findByIpdAdmissionIdAndStatus(admissionA.getId(), "ACTIVE")
                .stream().filter(p -> medicineId.equals(p.getMedicineId()))
                .findFirst().orElseThrow().getId();

        assertThat(post("/hospital/pharmacy/dispense/" + prescriptionId, doctorTokenA,
                "{\"quantity\":2}").getStatusCode().is2xxSuccessful())
                .as("viewing an order is not authority to move stock").isFalse();
        assertThat(rest.exchange("/hospital/pharmacy/dispense/medicines", HttpMethod.GET,
                        new HttpEntity<>(headers(doctorTokenA)), String.class)
                .getStatusCode().is2xxSuccessful())
                .as("nor to browse what could be moved").isFalse();
        assertThat(usableStock(medicineId)).isEqualTo(20);
    }

    /** The same dispensing workflow, on the clinic aliases, for a clinic tenant. */
    @Test
    void aClinicDispensesThroughTheSameWorkflow() {
        Hospital clinic = tenant("ClinicDisp", "CLINIC");
        String clinicAdmin = tokenFor(clinic, "HOSPITAL_ADMIN", "CLINIC");
        String name = "Clinic cefixime " + uniq();
        LocalDate expiry = LocalDate.now().plusMonths(5);

        assertThat(post("/clinic/medicines/purchases", clinicAdmin, purchaseBody(name, 30, expiry, "CB1"))
                .getStatusCode().value()).isEqualTo(200);
        Long medicineId = medicines.findByHospitalId(clinic.getId()).stream()
                .filter(m -> name.equalsIgnoreCase(m.getName())).findFirst().orElseThrow().getId();

        Map<String, Object> option = dispensableMedicines(clinicAdmin, "/clinic", null).stream()
                .filter(o -> name.equals(o.get("name"))).findFirst().orElseThrow();
        assertThat(((Number) option.get("availableQuantity")).intValue()).isEqualTo(30);
        assertThat(option.get("earliestExpiry")).isEqualTo(expiry.toString());

        // An OPD-style order for this clinic, then a dispense against it.
        Doctor clinicDoctor = new Doctor();
        clinicDoctor.setHospitalId(clinic.getId());
        clinicDoctor.setName("Dr clinic");
        clinicDoctor.setEmail("clinicdoc." + uniq() + "@inv.test");
        clinicDoctor.setPublicId("cdpub-" + uniq());
        clinicDoctor.setPhone("9800000003");
        clinicDoctor.setSpecialization("Gen");
        clinicDoctor.setIsActive(true);
        clinicDoctor = doctors.save(clinicDoctor);

        com.hms.entity.MedicalRecord record = new com.hms.entity.MedicalRecord();
        record.setHospitalId(clinic.getId());
        record.setPatientId(1L);
        record.setDoctorId(clinicDoctor.getId());
        record.setVisitType("OPD");
        record = medicalRecords.save(record);

        com.hms.entity.Prescription order = new com.hms.entity.Prescription();
        order.setHospitalId(clinic.getId());
        order.setMedicalRecordId(record.getId());
        order.setMedicineName(name);
        order.setStatus("ACTIVE");
        order.setType("TABLET");
        order.setRoute("ORAL");
        order = prescriptions.save(order);

        assertThat(post("/clinic/pharmacy/dispense/" + order.getId(), clinicAdmin,
                "{\"quantity\":7,\"medicineId\":" + medicineId + "}")
                .getStatusCode().value()).isEqualTo(200);

        assertThat(batches.availableQuantity(clinic.getId(), medicineId, LocalDate.now())).isEqualTo(23);
        assertThat(movements.dispensedForPrescription(clinic.getId(), order.getId())).isEqualTo(7);
        assertThat(prescriptions.findById(order.getId()).orElseThrow().getMedicineId())
                .as("the reconciliation is remembered").isEqualTo(medicineId);
    }
}

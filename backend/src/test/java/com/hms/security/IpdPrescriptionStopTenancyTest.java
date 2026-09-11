package com.hms.security;

import com.hms.entity.Hospital;
import com.hms.entity.MedicalRecord;
import com.hms.entity.PatientNurseAssignment;
import com.hms.entity.Prescription;
import com.hms.repository.AuditLogRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.MedicalRecordRepository;
import com.hms.repository.NotificationRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.repository.PrescriptionRepository;
import com.hms.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves IPD prescription stopping cannot cross the authenticated hospital boundary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IpdPrescriptionStopTenancyTest {

    private static final List<String> MODULES = List.of("OPD", "IPD", "NURSING");

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired MedicalRecordRepository medicalRecordRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired PatientNurseAssignmentRepository assignmentRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AuditLogRepository auditLogRepository;

    private long hospitalA;
    private long hospitalB;
    private long prescriptionA;
    private long prescriptionB;
    private String tokenA;
    private String tokenB;

    private String uniq() {
        return Long.toString(System.nanoTime());
    }

    @BeforeEach
    void setUp() {
        hospitalA = newHospital("alpha");
        hospitalB = newHospital("bravo");
        prescriptionA = newIpdPrescription(hospitalA, "alpha");
        prescriptionB = newIpdPrescription(hospitalB, "bravo");
        tokenA = token(hospitalA, "admin@alpha.test");
        tokenB = token(hospitalB, "admin@bravo.test");
    }

    @Test
    void sameTenantCanStopItsIpdPrescriptionAndNotifyTheAssignedNurse() {
        long notificationsBefore = stoppedNotifications();
        long auditsBefore = auditLogRepository.count();

        ResponseEntity<String> response = stop(tokenA, prescriptionA);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(prescriptionRepository.findById(prescriptionA).orElseThrow().getStatus()).isEqualTo("STOPPED");
        assertThat(stoppedNotifications()).isEqualTo(notificationsBefore + 1);
        assertThat(auditLogRepository.count()).as("stop has no audit side effect today").isEqualTo(auditsBefore);
    }

    @Test
    void tenantBCannotStopTenantAsPrescriptionOrNotifyItsNurse() {
        assertRejectedAndInert(tokenB, prescriptionA);
    }

    @Test
    void tenantACannotStopTenantBsPrescriptionOrNotifyItsNurse() {
        assertRejectedAndInert(tokenA, prescriptionB);
    }

    @Test
    void foreignAndMissingPrescriptionIdsHaveTheSameNotFoundContract() {
        String foreign = normalized(stop(tokenB, prescriptionA).getBody());
        String missing = normalized(stop(tokenB, 9_999_999_999L).getBody());

        assertThat(foreign).isEqualTo(missing);
    }

    private void assertRejectedAndInert(String token, long prescriptionId) {
        Prescription before = prescriptionRepository.findById(prescriptionId).orElseThrow();
        long notificationsBefore = stoppedNotifications();
        long auditsBefore = auditLogRepository.count();

        ResponseEntity<String> response = stop(token, prescriptionId);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("\"code\":\"RESOURCE_NOT_FOUND\"");
        assertThat(prescriptionRepository.findById(prescriptionId).orElseThrow().getStatus())
                .as("foreign prescription remains unchanged").isEqualTo(before.getStatus());
        assertThat(stoppedNotifications()).as("no nurse notification").isEqualTo(notificationsBefore);
        assertThat(auditLogRepository.count()).as("no audit row").isEqualTo(auditsBefore);
    }

    private long newHospital(String name) {
        Hospital hospital = new Hospital();
        hospital.setName("H-" + name);
        hospital.setCustomId("HID-" + uniq());
        hospital.setSubscriptionStatus("ACTIVE");
        hospital.setIsActive(true);
        hospital.setModules(MODULES);
        hospital.setIsSingleDoctor(false);
        return hospitalRepository.save(hospital).getId();
    }

    private long newIpdPrescription(long hospitalId, String label) {
        long ipdId = Math.abs(System.nanoTime());
        MedicalRecord record = new MedicalRecord();
        record.setHospitalId(hospitalId);
        record.setPatientId(100L + hospitalId);
        record.setDoctorId(200L + hospitalId);
        record.setIpdAdmissionId(ipdId);
        record.setVisitType("IPD");
        long recordId = medicalRecordRepository.save(record).getId();

        Prescription prescription = new Prescription();
        prescription.setHospitalId(hospitalId);
        prescription.setMedicalRecordId(recordId);
        prescription.setMedicineName("Medicine-" + label);
        prescription.setStatus("ACTIVE");
        long prescriptionId = prescriptionRepository.save(prescription).getId();

        PatientNurseAssignment assignment = new PatientNurseAssignment();
        assignment.setHospitalId(hospitalId);
        assignment.setIpdAdmissionId(ipdId);
        assignment.setPatientId(record.getPatientId());
        assignment.setNurseUserId(300L + hospitalId);
        assignment.setAssignedByUserId(400L + hospitalId);
        assignment.setIsActive(true);
        assignmentRepository.save(assignment);
        return prescriptionId;
    }

    private String token(long hospitalId, String email) {
        return jwtUtil.generateToken(1L, email, "HOSPITAL_ADMIN", hospitalId,
                MODULES, null, "HOSPITAL", null);
    }

    private ResponseEntity<String> stop(String token, long prescriptionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Forwarded-For", "10.208.0." + (prescriptionId % 200 + 1));
        return rest.exchange("/hospital/ipd/prescriptions/" + prescriptionId + "/stop",
                HttpMethod.PUT, new HttpEntity<>(headers), String.class);
    }

    private long stoppedNotifications() {
        return notificationRepository.findAll().stream()
                .filter(notification -> "PRESCRIPTION_STOPPED".equals(notification.getType()))
                .count();
    }

    private String normalized(String body) {
        return body == null ? null : body.replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"X\"");
    }
}

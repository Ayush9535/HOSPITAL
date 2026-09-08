package com.hms.service;

import com.hms.entity.Patient;
import com.hms.repository.*;
import com.hms.security.SecurityContextHelper;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.service.AuditLogService;
import com.hms.service.hospital.PatientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientServiceConsultationTest {

    @Mock PatientRepository patientRepository;
    @Mock CacheManager cacheManager;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock HospitalWebSocketHandler webSocketHandler;
    @Mock BillingRepository billingRepository;
    @Mock PrescriptionRepository prescriptionRepository;
    @Mock DoctorRepository doctorRepository;
    @Mock MedicalRecordRepository medicalRecordRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock BillingItemRepository billingItemRepository;
    @Mock BillingMedicineRepository billingMedicineRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock BedRepository bedRepository;
    @Mock WardRepository wardRepository;
    @Mock IpdBedHistoryRepository ipdBedHistoryRepository;
    @Mock OpdRepository opdRepository;
    @Mock HospitalRepository hospitalRepository;
    @Mock DischargeSummaryRepository dischargeSummaryRepository;

    @InjectMocks PatientService service;

    private Patient patient;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setId(42L);
        patient.setCustomId("HMS-P-0042");
        patient.setPublicId("some-uuid");
        patient.setHospitalId(1L);
        patient.setName("John Doe");
        patient.setGender("Male");
        patient.setPhone("9876543210");
    }

    @Test
    void getPatientConsultationDetails_includesCustomIdAndNumericId() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(patientRepository.findById(42L)).thenReturn(Optional.of(patient));
        when(medicalRecordRepository.findByPatientIdOrderByCreatedAtDesc(42L))
                .thenReturn(List.of());
        when(ipdAdmissionRepository.findByPatientIdOrderByAdmissionDatetimeDesc(42L))
                .thenReturn(List.of());

        Map<String, Object> result = service.getPatientConsultationDetails("42");

        assertThat(result).containsKey("patient");
        @SuppressWarnings("unchecked")
        Map<String, Object> patientData = (Map<String, Object>) result.get("patient");
        assertThat(patientData).containsEntry("customId", "HMS-P-0042");
        assertThat(patientData).containsEntry("id", 42L);
    }

    @Test
    void getPatientConsultationDetails_withPublicId_returnsPatientData() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(patientRepository.findByPublicIdAndHospitalIdAndIsActiveTrue("some-uuid", 1L))
                .thenReturn(Optional.of(patient));
        when(medicalRecordRepository.findByPatientIdOrderByCreatedAtDesc(42L))
                .thenReturn(List.of());
        when(ipdAdmissionRepository.findByPatientIdOrderByAdmissionDatetimeDesc(42L))
                .thenReturn(List.of());

        Map<String, Object> result = service.getPatientConsultationDetails("some-uuid");

        @SuppressWarnings("unchecked")
        Map<String, Object> patientData = (Map<String, Object>) result.get("patient");
        assertThat(patientData.get("customId")).isEqualTo("HMS-P-0042");
    }
}

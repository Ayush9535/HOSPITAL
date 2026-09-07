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

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientServiceDateOfBirthTest {

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

    private Patient newPatient(LocalDate dob) {
        Patient p = new Patient();
        p.setName("Jane Doe");
        p.setPhone("9876543210");
        p.setGender("FEMALE");
        p.setDateOfBirth(dob);
        return p;
    }

    @Test
    void addPatient_missingDateOfBirth_throws() {
        lenient().when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        Patient patient = newPatient(null);

        assertThatThrownBy(() -> service.addPatient(patient))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Date of birth is required");
    }

    @Test
    void addPatient_futureDateOfBirth_throws() {
        lenient().when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        Patient patient = newPatient(LocalDate.now().plusDays(1));

        assertThatThrownBy(() -> service.addPatient(patient))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be in the future");
    }

    @Test
    void addPatient_dateOfBirthOver120YearsAgo_throws() {
        lenient().when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        Patient patient = newPatient(LocalDate.now().minusYears(121));

        assertThatThrownBy(() -> service.addPatient(patient))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("120 years");
    }

    @Test
    void addPatient_validDateOfBirth_savesPatient() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        Patient patient = newPatient(LocalDate.now().minusYears(30));
        Patient saved = newPatient(LocalDate.now().minusYears(30));
        saved.setId(5L);
        when(patientRepository.save(any(Patient.class))).thenReturn(saved, saved);

        Patient result = service.addPatient(patient);

        assertThat(result.getDateOfBirth()).isEqualTo(LocalDate.now().minusYears(30));
        verify(patientRepository, atLeastOnce()).save(any(Patient.class));
    }

    @Test
    void updatePatient_setsNewDateOfBirth() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        Patient existing = newPatient(LocalDate.now().minusYears(40));
        existing.setId(7L);
        existing.setHospitalId(1L);
        when(patientRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate correctedDob = LocalDate.now().minusYears(41);
        Patient updateData = newPatient(correctedDob);

        Patient result = service.updatePatient(7L, updateData);

        assertThat(result.getDateOfBirth()).isEqualTo(correctedDob);
    }
}

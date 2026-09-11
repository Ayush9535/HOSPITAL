package com.hms.service;

import com.hms.dto.PlanDischargeRequest;
import com.hms.entity.Bed;
import com.hms.entity.Billing;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.repository.*;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import com.hms.service.hospital.BillingService;
import com.hms.service.hospital.HospitalInventoryService;
import com.hms.service.hospital.IpdAdmissionService;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IpdAdmissionServiceTest {

    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock PatientRepository patientRepository;
    @Mock HospitalSettingRepository hospitalSettingRepository;
    @Mock DoctorRepository doctorRepository;
    @Mock WardRepository wardRepository;
    @Mock OpdRepository opdRepository;
    @Mock BedRepository bedRepository;
    @Mock BillingRepository billingRepository;
    @Mock BillingService billingService;
    @Mock AuditLogService auditLogService;
    @Mock MedicalRecordRepository medicalRecordRepository;
    @Mock PrescriptionRepository prescriptionRepository;
    @Mock MedicineRepository medicineRepository;
    @Mock BillingItemRepository billingItemRepository;
    @Mock BillingMedicineRepository billingMedicineRepository;
    @Mock DischargeSummaryRepository dischargeSummaryRepository;
    @Mock BillingPaymentRepository billingPaymentRepository;
    @Mock AppointmentRepository appointmentRepository;
    @Mock HospitalRepository hospitalRepository;
    @Mock IpdBedHistoryRepository ipdBedHistoryRepository;
    @Mock com.hms.service.hospital.icu.IcuStayService icuStayService;
    @Mock SecurityContextHelper securityHelper;
    @Mock QueueEntryRepository queueEntryRepository;
    @Mock HospitalInventoryRepository hospitalInventoryRepository;
    @Mock HospitalInventoryService hospitalInventoryService;
    @Mock HospitalWebSocketHandler webSocketHandler;
    @Mock com.hms.service.hospital.PatientAssignmentService patientAssignmentService;
    @Mock com.hms.service.hospital.BedStatusService bedStatusService;

    @InjectMocks
    IpdAdmissionService service;

    @Test
    void admitFromOpd_withOccupiedBed_throwsRuntimeException() {
        Opd opd = new Opd();
        opd.setId(1L);
        Patient patient = new Patient();
        patient.setId(5L);
        opd.setPatient(patient);

        when(opdRepository.findByIdAndHospitalIdWithPatientAndDoctorForUpdate(1L, 1L)).thenReturn(Optional.of(opd));
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(patientRepository.findByIdAndHospitalIdForUpdate(5L, 1L)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findGlobalIpdNumberLock()).thenReturn(Optional.of(new com.hms.entity.Hospital()));

        Bed bed = new Bed();
        bed.setBedId(2L);
        bed.setWardId(1L);
        bed.setStatus("occupied");

        when(bedRepository.findByBedIdAndHospitalIdForUpdate(2L, 1L)).thenReturn(Optional.of(bed));

        assertThatThrownBy(() -> service.admitFromOpd(1L, 1L, 2L, "ELECTIVE", "Fever"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Bed is no longer available");
    }

    @Test
    void admitFromOpd_withAvailableBed_savesAdmissionAndMarksBedOccupied() {
        Patient patient = new Patient();
        patient.setId(5L);

        Opd opd = new Opd();
        opd.setId(1L);
        opd.setPatient(patient);

        when(opdRepository.findByIdAndHospitalIdWithPatientAndDoctorForUpdate(1L, 1L)).thenReturn(Optional.of(opd));
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(securityHelper.getCurrentUserEmail()).thenReturn("admin@test.com");
        when(patientRepository.findByIdAndHospitalIdForUpdate(5L, 1L)).thenReturn(Optional.of(patient));

        Bed bed = new Bed();
        bed.setBedId(2L);
        bed.setWardId(1L);
        bed.setStatus("available");

        when(bedRepository.findByBedIdAndHospitalIdForUpdate(2L, 1L)).thenReturn(Optional.of(bed));
        when(ipdAdmissionRepository.findMaxIpdSequence()).thenReturn(0L);

        Bed occupiedBed = new Bed();
        occupiedBed.setBedId(2L);
        occupiedBed.setStatus("occupied");
        when(bedStatusService.changeLocked(eq(bed), eq(com.hms.entity.BedStatus.OCCUPIED), any()))
                .thenReturn(occupiedBed);

        IpdAdmission savedIpd = new IpdAdmission();
        savedIpd.setId(10L);
        savedIpd.setIpdNumber("IPD-1");
        savedIpd.setPatientId(5L);
        savedIpd.setHospitalId(1L);

        com.hms.entity.Hospital hospital = new com.hms.entity.Hospital();
        hospital.setId(1L);
        hospital.setModules(java.util.List.of("BILLING", "NURSING"));
        when(hospitalRepository.findById(1L)).thenReturn(Optional.of(hospital));
        when(hospitalRepository.findGlobalIpdNumberLock()).thenReturn(Optional.of(hospital));
        when(ipdAdmissionRepository.save(any(IpdAdmission.class))).thenReturn(savedIpd);
        when(billingRepository.save(any(Billing.class))).thenAnswer(i -> i.getArguments()[0]);
        when(ipdBedHistoryRepository.save(any(com.hms.entity.IpdBedHistory.class))).thenAnswer(i -> i.getArguments()[0]);
        com.hms.entity.Ward ward = new com.hms.entity.Ward();
        ward.setWardId(1L);
        ward.setInchargeNurseId(null);
        when(wardRepository.findByWardIdAndHospitalId(1L, 1L)).thenReturn(Optional.of(ward));
        when(appointmentRepository.findByPatientIdAndHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(any(), any()))
                .thenReturn(List.of());

        IpdAdmission result = service.admitFromOpd(1L, 1L, 2L, "ELECTIVE", "Fever");

        assertThat(result).isNotNull();

        ArgumentCaptor<Bed> bedCaptor = ArgumentCaptor.forClass(Bed.class);
        verify(bedRepository).save(bedCaptor.capture());
        assertThat(bedCaptor.getValue().getStatus()).isEqualTo("occupied");
    }

    @Test
    void planDischarge_withNonDoctorRole_throwsAccessDeniedException() {
        when(securityHelper.getCurrentUserRole()).thenReturn("RECEPTIONIST");

        assertThatThrownBy(() -> service.planDischarge(1L, new PlanDischargeRequest()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void planDischarge_whenPatientNotAdmitted_throwsRuntimeException() {
        when(securityHelper.getCurrentUserRole()).thenReturn("DOCTOR");
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);

        IpdAdmission ipd = new IpdAdmission();
        ipd.setId(1L);
        ipd.setHospitalId(1L); // must belong to the caller's hospital to reach the status check
        ipd.setStatus("DISCHARGED");

        when(ipdAdmissionRepository.findByIdAndHospitalIdForUpdate(1L, 1L)).thenReturn(Optional.of(ipd));

        assertThatThrownBy(() -> service.planDischarge(1L, new PlanDischargeRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Can only plan discharge for ADMITTED patients");
    }

    @Test
    void addIpdPrescription_withStartDateInPast_throwsIllegalArgumentException() {
        when(securityHelper.getCurrentUserRole()).thenReturn("DOCTOR");
        
        IpdAdmission ipd = new IpdAdmission();
        ipd.setId(1L);
        ipd.setHospitalId(1L); // must belong to the caller's hospital to reach validation
        ipd.setStatus("ADMITTED");
        when(ipdAdmissionRepository.findByIdAndHospitalIdForUpdate(1L, 1L)).thenReturn(Optional.of(ipd));
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        
        com.hms.dto.AddIpdPrescriptionRequest req = new com.hms.dto.AddIpdPrescriptionRequest();
        req.setMedicineId(5L);
        req.setDose("1 tab");
        req.setFrequency("1-0-1");
        req.setType("TABLET");
        req.setRoute("ORAL");
        req.setDurationDays(5);
        req.setStartDate(java.time.LocalDate.now().minusDays(1)); // past date
        
        assertThatThrownBy(() -> service.addIpdPrescription(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prescription start date cannot be in the past");
    }
}

package com.hms.service;

import com.hms.entity.Appointment;
import com.hms.entity.Doctor;
import com.hms.entity.Patient;
import com.hms.repository.AppointmentRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.PatientRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.hospital.AppointmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceNewPatientTest {

    @Mock PatientRepository patientRepository;
    @Mock DoctorRepository doctorRepository;
    @Mock AppointmentRepository appointmentRepository;
    @Mock SecurityContextHelper securityHelper;

    @InjectMocks AppointmentService service;

    @Captor ArgumentCaptor<Patient> patientCaptor;

    private Appointment newWalkInAppointment(String phone, LocalDate patientDob) {
        Appointment appointment = new Appointment();
        appointment.setPatientName("Walk-in Patient");
        appointment.setPatientPhone(phone);
        appointment.setPatientDateOfBirth(patientDob);
        appointment.setPatientGender("MALE");
        appointment.setDoctorId(1L);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setAppointmentTime(LocalTime.of(10, 0));
        return appointment;
    }

    private void mockCommonCollaborators(String phone) {
        // Module validity is normalized at the plan boundary; this service only enforces tenant
        // ownership for the patient and doctor referenced by the booking.
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(patientRepository.findByPhoneAndHospitalIdAndIsActiveTrue(phone, 1L))
                .thenReturn(Collections.emptyList());
        Doctor doctor = new Doctor();
        doctor.setId(1L);
        doctor.setName("Dr. Test");
        when(doctorRepository.findByIdAndHospitalIdAndIsActiveTrue(1L, 1L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findByDoctorIdAndAppointmentDateAndIsActiveTrue(any(), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createAppointment_newPatientWithDob_setsDateOfBirthOnCreatedPatient() {
        mockCommonCollaborators("9876543210");
        Patient saved = new Patient();
        saved.setId(9L);
        when(patientRepository.save(any(Patient.class))).thenReturn(saved);

        Appointment appointment = newWalkInAppointment("9876543210", LocalDate.now().minusYears(25));

        service.createAppointment(appointment);

        verify(patientRepository).save(patientCaptor.capture());
        assertThat(patientCaptor.getValue().getDateOfBirth()).isEqualTo(LocalDate.now().minusYears(25));
    }

    @Test
    void createAppointment_newPatientNoDob_defaultsToToday() {
        mockCommonCollaborators("9876543211");
        Patient saved = new Patient();
        saved.setId(10L);
        when(patientRepository.save(any(Patient.class))).thenReturn(saved);

        Appointment appointment = newWalkInAppointment("9876543211", null);

        service.createAppointment(appointment);

        verify(patientRepository).save(patientCaptor.capture());
        assertThat(patientCaptor.getValue().getDateOfBirth()).isEqualTo(LocalDate.now());
    }

}

package com.hms.service.hospital;

import com.hms.entity.Appointment;
import com.hms.entity.Doctor;
import com.hms.entity.Patient;
import com.hms.repository.AppointmentRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.PatientRepository;
import com.hms.security.SecurityContextHelper;
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
    // The insert itself belongs to PatientRegistrar, which is where the registration number is
    // assigned. These two tests are about the date of birth this service puts on the entity
    // before handing it over, so the insert is stubbed to hand the same instance back.
    @Mock PatientRegistrar patientRegistrar;
    @Mock com.hms.service.hospital.PatientDuplicateFinder patientDuplicateFinder;

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
        // Nobody at this hospital is on this number, so the booking creates a patient rather
        // than stopping to ask which of several people sharing it is being booked.
        when(patientDuplicateFinder.findActiveByPhone(1L, phone, null))
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
        when(patientRegistrar.persistNewPatient(any(Patient.class))).thenAnswer(inv -> {
            Patient p = inv.getArgument(0);
            p.setId(9L);
            return p;
        });

        Appointment appointment = newWalkInAppointment("9876543210", LocalDate.now().minusYears(25));

        service.createAppointment(appointment);

        verify(patientRegistrar).persistNewPatient(patientCaptor.capture());
        assertThat(patientCaptor.getValue().getDateOfBirth()).isEqualTo(LocalDate.now().minusYears(25));
    }

    @Test
    void createAppointment_newPatientNoDob_defaultsToToday() {
        mockCommonCollaborators("9876543211");
        when(patientRegistrar.persistNewPatient(any(Patient.class))).thenAnswer(inv -> {
            Patient p = inv.getArgument(0);
            p.setId(10L);
            return p;
        });

        Appointment appointment = newWalkInAppointment("9876543211", null);

        service.createAppointment(appointment);

        verify(patientRegistrar).persistNewPatient(patientCaptor.capture());
        assertThat(patientCaptor.getValue().getDateOfBirth()).isEqualTo(LocalDate.now());
    }

}

package com.hms.component;

import com.hms.entity.*;
import com.hms.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PublicIdBackfillRunner - Backfills missing public IDs on startup
 * 
 * Takes care of existing data migration.
 */
@Component
public class PublicIdBackfillRunner implements CommandLineRunner {

    @Autowired
    private HospitalRepository hospitalRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DoctorRepository doctorRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private BillingRepository billingRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    @Override
    public void run(String... args) throws Exception {
        backfillHospitals();
        backfillUsers();
        backfillDoctors();
        backfillPatients();
        backfillAppointments();
        backfillBillings();
        backfillAuditLogs();
    }

    private void backfillHospitals() {
        List<Hospital> list = hospitalRepository.findAll();
        boolean updated = false;
        for (Hospital e : list) {
            boolean changed = false;
            if (e.getPublicId() == null || e.getPublicId().trim().isEmpty()) {
                e.setPublicId(java.util.UUID.randomUUID().toString());
                changed = true;
            }
            if (e.getCustomId() == null) {
                e.generateIds(); // Will handle customId
                changed = true;
            }
            // Backfill default consultation fee for CMS testing
            if (e.getConsultationFee() == null || e.getConsultationFee().compareTo(java.math.BigDecimal.ZERO) == 0) {
                e.setConsultationFee(new java.math.BigDecimal("500.00"));
                changed = true;
            }

            // Backfill default modules
            if (e.getModules() == null || e.getModules().isEmpty()) {
                e.setModules(new java.util.ArrayList<>(java.util.Arrays.asList("OPD", "BILLING")));
                changed = true;
            }

            if (changed) {
                hospitalRepository.save(e);
                updated = true;
            }
        }
    }

    private void backfillUsers() {
        List<User> list = userRepository.findAll();

        boolean updated = false;
        for (User e : list) {
            boolean changed = false;
            // Robust check for missing Public ID
            if (e.getPublicId() == null || e.getPublicId().trim().isEmpty() || "null".equals(e.getPublicId())) {
                e.setPublicId(java.util.UUID.randomUUID().toString());
                changed = true;
            }
            if (e.getCustomId() == null) {
                e.generateIds(); // Ensures customId is set
                changed = true;
            }
            if (changed) {
                userRepository.save(e);
                updated = true;
            }
        }
    }

    private void backfillDoctors() {
        List<Doctor> list = doctorRepository.findAll();
        boolean updated = false;
        for (Doctor e : list) {
            if (e.getPublicId() == null || e.getCustomId() == null) {
                e.generateIds();
                doctorRepository.save(e);
                updated = true;
            }
        }
    }

    private void backfillPatients() {
        List<Patient> list = patientRepository.findAll();
        boolean updated = false;
        for (Patient e : list) {
            boolean changed = false;
            if (e.getPublicId() == null || e.getPublicId().trim().isEmpty()) {
                e.setPublicId(java.util.UUID.randomUUID().toString());
                changed = true;
            }
            if (e.getCustomId() == null) {
                e.generateIds();
                changed = true;
            }

            if (changed) {
                patientRepository.save(e);
                updated = true;
            }
        }
    }

    private void backfillAppointments() {
        List<Appointment> list = appointmentRepository.findAll();
        boolean updated = false;
        for (Appointment e : list) {
            if (e.getPublicId() == null || e.getCustomId() == null) {
                e.generateIds();
                appointmentRepository.save(e);
                updated = true;
            }
        }
    }

    private void backfillBillings() {
        List<Billing> list = billingRepository.findAll();
        boolean updated = false;
        for (Billing e : list) {
            if (e.getPublicId() == null || e.getCustomId() == null) {
                e.generateIds();
                billingRepository.save(e);
                updated = true;
            }
        }
    }

    private void backfillAuditLogs() {
        List<AuditLog> list = auditLogRepository.findAll();
        boolean updated = false;
        for (AuditLog e : list) {
            // AuditLog doesn't have customId yet, just publicId
            if (e.getPublicId() == null) {
                e.generatePublicId();
                auditLogRepository.save(e);
                updated = true;
            }
        }
    }
}

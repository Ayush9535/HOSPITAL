package com.hms.support;

import com.hms.entity.*;
import com.hms.repository.*;
import com.hms.security.JwtUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persists a complete, authenticated nursing test tenant.  It deliberately mints
 * tokens from saved users so HTTP tests do not depend on shared H2 rows.
 */
public final class NursingHttpFixture {
    public static final List<String> MODULES = List.of("OPD", "IPD", "NURSING");
    private static final AtomicLong SEQ = new AtomicLong();

    private final JwtUtil jwtUtil;
    private final HospitalRepository hospitals;
    private final HospitalSettingRepository settings;
    private final UserRepository users;
    private final NurseProfileRepository nurses;
    private final WardRepository wards;
    private final BedRepository beds;
    private final PatientRepository patients;
    private final DoctorRepository doctors;
    private final IpdAdmissionRepository admissions;
    private final MedicalRecordRepository records;
    private final PrescriptionRepository prescriptions;
    private final IpdBedHistoryRepository bedHistory;

    public NursingHttpFixture(JwtUtil jwtUtil, HospitalRepository hospitals,
            HospitalSettingRepository settings, UserRepository users, NurseProfileRepository nurses,
            WardRepository wards, BedRepository beds, PatientRepository patients,
            DoctorRepository doctors, IpdAdmissionRepository admissions,
            MedicalRecordRepository records, PrescriptionRepository prescriptions, IpdBedHistoryRepository bedHistory) {
        this.jwtUtil = jwtUtil;
        this.hospitals = hospitals;
        this.settings = settings;
        this.users = users;
        this.nurses = nurses;
        this.wards = wards;
        this.beds = beds;
        this.patients = patients;
        this.doctors = doctors;
        this.admissions = admissions;
        this.records = records;
        this.prescriptions = prescriptions;
        this.bedHistory = bedHistory;
    }

    public Hospital tenant(String label) {
        Hospital hospital = new Hospital();
        hospital.setName("Nursing " + label);
        hospital.setCustomId("NH-" + unique());
        hospital.setIsActive(true);
        hospital.setSubscriptionStatus("ACTIVE");
        hospital.setModules(MODULES);
        hospital = hospitals.save(hospital);
        HospitalSetting setting = new HospitalSetting();
        setting.setHospital(hospital);
        setting.setSeparateNurseLogin(true);
        settings.save(setting);
        return hospital;
    }

    public Ward ward(Hospital hospital, String label) {
        Ward ward = new Ward();
        ward.setHospitalId(hospital.getId());
        ward.setWardName("Ward " + label + " " + unique());
        ward.setTotalBeds(4);
        ward.setBedPrice(BigDecimal.ZERO);
        return wards.save(ward);
    }

    public Bed bed(Hospital hospital, Ward ward, String label) {
        Bed bed = new Bed();
        bed.setHospitalId(hospital.getId());
        bed.setWardId(ward.getWardId());
        bed.setBedCode("BED-" + label + "-" + unique());
        bed.setStatus(BedStatus.OCCUPIED);
        return beds.save(bed);
    }

    public Bed availableBed(Hospital hospital, Ward ward, String label) {
        Bed bed = bed(hospital, ward, label);
        bed.setStatus(BedStatus.AVAILABLE);
        return beds.save(bed);
    }

    public User user(Hospital hospital, String role, String label) {
        User user = new User();
        user.setEmail(label + "." + unique() + "@nursing.test");
        user.setPassword("{noop}fixture");
        user.setName(label);
        user.setRole(role);
        user.setHospitalId(hospital.getId());
        user.setIsActive(true);
        user.setTokenVersion(0);
        return users.save(user);
    }

    public NurseProfile staffNurse(Hospital hospital, Ward ward, String label) {
        return nurse(hospital, ward, label, false, "NURSE");
    }

    public NurseProfile incharge(Hospital hospital, Ward ward, String label) {
        NurseProfile profile = nurse(hospital, ward, label, true, "NURSE_INCHARGE");
        ward.setInchargeNurseId(profile.getId());
        wards.save(ward);
        return profile;
    }

    private NurseProfile nurse(Hospital hospital, Ward ward, String label, boolean incharge, String role) {
        User user = user(hospital, role, label);
        NurseProfile profile = new NurseProfile();
        profile.setHospitalId(hospital.getId());
        profile.setUserId(user.getId());
        profile.setName(label);
        profile.setEmail(user.getEmail());
        profile.setWardId(ward.getWardId());
        profile.setIsIncharge(incharge);
        profile.setIsActive(true);
        profile.setOnShift(true);
        return nurses.save(profile);
    }

    public Doctor doctor(Hospital hospital, String label) {
        Doctor doctor = new Doctor();
        doctor.setHospitalId(hospital.getId());
        doctor.setName("Dr " + label);
        doctor.setEmail("doctor." + unique() + "@nursing.test");
        doctor.setPhone("9800000000");
        doctor.setSpecialization("General");
        doctor.setIsActive(true);
        doctor.setPublicId("doctor-" + unique());
        return doctors.save(doctor);
    }

    public IpdAdmission admit(Hospital hospital, Ward ward, Bed bed, Doctor doctor, String label) {
        Patient patient = new Patient();
        patient.setHospitalId(hospital.getId());
        patient.setName("Patient " + label);
        patient.setPublicId("patient-" + unique());
        patient.setPhone("9900000000");
        patient.setGender("MALE");
        patient.setDateOfBirth(LocalDate.of(1985, 1, 1));
        patient.setIsActive(true);
        patient = patients.save(patient);

        IpdAdmission admission = new IpdAdmission();
        admission.setHospitalId(hospital.getId());
        admission.setPatientId(patient.getId());
        admission.setDoctorId(doctor.getId());
        admission.setIpdNumber("NIPD-" + unique());
        admission.setAdmissionType("ELECTIVE");
        admission.setStatus("ADMITTED");
        admission.setAdmissionDatetime(LocalDateTime.now());
        admission.setWardId(ward.getWardId());
        admission.setBedId(bed.getBedId());
        admission.setAdmissionConfirmed(true);
        admission = admissions.save(admission);
        bed.setCurrentIpdAdmissionId(admission.getId());
        beds.save(bed);
        IpdBedHistory history = new IpdBedHistory();
        history.setIpdAdmissionId(admission.getId());
        history.setWardId(ward.getWardId());
        history.setBedId(bed.getBedId());
        history.setAssignedAt(LocalDateTime.now());
        bedHistory.save(history);
        return admission;
    }

    public Prescription activePrescription(Hospital hospital, IpdAdmission admission, Doctor doctor) {
        MedicalRecord record = new MedicalRecord();
        record.setHospitalId(hospital.getId());
        record.setPatientId(admission.getPatientId());
        record.setDoctorId(doctor.getId());
        record.setIpdAdmissionId(admission.getId());
        record.setVisitType("IPD");
        record = records.save(record);
        Prescription prescription = new Prescription();
        prescription.setHospitalId(hospital.getId());
        prescription.setMedicalRecordId(record.getId());
        prescription.setMedicineName("Fixture medicine");
        prescription.setDosage("500mg");
        prescription.setFrequency("1-0-1");
        prescription.setDuration("3 days");
        prescription.setDurationDays(3);
        prescription.setStartDate(LocalDate.now());
        prescription.setStatus("ACTIVE");
        prescription.setType("TABLET");
        prescription.setRoute("ORAL");
        return prescriptions.save(prescription);
    }

    public String tokenFor(NurseProfile profile) {
        return tokenFor(users.findById(profile.getUserId()).orElseThrow());
    }

    public String tokenFor(User user) {
        return jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole(), user.getHospitalId(),
                MODULES, null, "HOSPITAL", null, user.getTokenVersion());
    }

    private static long unique() {
        return System.nanoTime() + SEQ.incrementAndGet();
    }
}

package com.hms.security;

import com.hms.entity.Appointment;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.User;
import com.hms.repository.AppointmentRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reported staging bug: an appointment is booked for a new patient and then nobody can find
 * it -- not the doctor, and seemingly not the system either.
 *
 * <p>Everything here is driven through the real endpoints against a real database, because the
 * question is specifically whether a row that exists is reachable by the queries the two screens
 * actually run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AppointmentVisibilityTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES = List.of("APPOINTMENTS", "OPD", "IPD", "BILLING");

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitals;
    @Autowired UserRepository users;
    @Autowired DoctorRepository doctors;
    @Autowired PatientRepository patients;
    @Autowired AppointmentRepository appointments;

    private Hospital hospital;
    private Doctor doctor;
    private String receptionToken;
    private String doctorToken;

    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }

    /** A distinct, validly-shaped 10-digit phone number per patient. */
    private static String phone() {
        return "9" + String.format("%09d", Math.floorMod(uniq(), 1_000_000_000L));
    }

    private Hospital tenant(String type) {
        Hospital h = new Hospital();
        h.setName("Appt " + type);
        h.setCustomId("APPT-" + uniq());
        h.setIsActive(true);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        h.setType(com.hms.entity.HospitalType.valueOf(type));
        return hospitals.save(h);
    }

    private User staff(Hospital h, String role, String email) {
        User u = new User();
        u.setEmail(email);
        u.setPassword("{noop}fixture");
        u.setName("User " + role);
        u.setRole(role);
        u.setHospitalId(h.getId());
        u.setIsActive(true);
        u.setTokenVersion(0);
        return users.save(u);
    }

    private String tokenFor(User u, String type) {
        return jwtUtil.generateToken(u.getId(), u.getEmail(), u.getRole(), u.getHospitalId(),
                MODULES, null, type, null, u.getTokenVersion());
    }

    private Doctor seedDoctor(Hospital h, String email) {
        Doctor d = new Doctor();
        d.setHospitalId(h.getId());
        d.setName("Dr Visible");
        d.setEmail(email);
        d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000010");
        d.setSpecialization("Gen");
        d.setIsActive(true);
        return doctors.save(d);
    }

    @BeforeEach
    void setUp() {
        hospital = tenant("HOSPITAL");
        String docEmail = "apptdoc." + uniq() + "@appt.test";
        doctor = seedDoctor(hospital, docEmail);
        receptionToken = tokenFor(staff(hospital, "RECEPTIONIST", "rec." + uniq() + "@appt.test"), "HOSPITAL");
        doctorToken = tokenFor(staff(hospital, "DOCTOR", docEmail), "HOSPITAL");
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String bookingBody(Long doctorId, LocalDate date, String time, String patientPhone) {
        return "{\"doctorId\":" + doctorId
                + ",\"appointmentDate\":\"" + date + "\""
                + ",\"appointmentTime\":\"" + time + "\""
                + ",\"patientName\":\"Walkin Patient\""
                + ",\"patientPhone\":\"" + patientPhone + "\""
                + ",\"patientGender\":\"MALE\"}";
    }

    private ResponseEntity<String> book(String prefix, String token, String body) {
        return rest.exchange(prefix + "/appointments", HttpMethod.POST,
                new HttpEntity<>(body, headers(token)), String.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pageContent(String url, String token) {
        ResponseEntity<Map> res = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers(token)), Map.class);
        assertThat(res.getStatusCode().value()).as("%s", url).isEqualTo(200);
        Object content = res.getBody().get("content");
        return content == null ? List.of() : (List<Map<String, Object>>) content;
    }

    private boolean containsAppointment(List<Map<String, Object>> rows, Long id) {
        return rows.stream().anyMatch(r -> id.equals(((Number) r.get("id")).longValue()));
    }

    /**
     * The golden path. A booking made today for tomorrow must be findable by everyone who is
     * supposed to see it, and must still be there after a fresh read.
     */
    @Test
    void anAppointmentBookedForANewPatientIsVisibleToTheHospitalAndToItsDoctor() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        ResponseEntity<String> created = book("/hospital", receptionToken,
                bookingBody(doctor.getId(), tomorrow, "10:30", phone()));
        assertThat(created.getStatusCode().value()).as("%s", created.getBody()).isEqualTo(200);

        // It is really in the database, active, and attached to the right doctor and facility.
        Appointment row = appointments.findAll().stream()
                .filter(a -> hospital.getId().equals(a.getHospitalId()))
                .findFirst().orElseThrow();
        assertThat(row.getIsActive()).isTrue();
        assertThat(row.getDoctorId()).isEqualTo(doctor.getId());
        assertThat(row.getAppointmentDate()).isEqualTo(tomorrow);
        assertThat(row.getStatus()).isEqualTo("SCHEDULED");
        assertThat(row.getPatientId()).as("the walk-in patient was created and linked").isNotNull();

        // The hospital's own appointment list finds it.
        assertThat(containsAppointment(pageContent("/hospital/appointments?page=0&size=50", receptionToken), row.getId()))
                .as("the hospital appointment list must show a booking it just took").isTrue();

        // And so does the doctor's own list.
        assertThat(containsAppointment(
                pageContent("/hospital/appointments/my-appointments?page=0&size=50", doctorToken), row.getId()))
                .as("the assigned doctor must see their own appointment").isTrue();

        // Reading it again changes nothing -- this is the "refresh and it is gone" complaint.
        assertThat(containsAppointment(
                pageContent("/hospital/appointments/my-appointments?page=0&size=50", doctorToken), row.getId()))
                .isTrue();
    }

    /**
     * The doctor's screen opens on "Today" and offers "Upcoming" and "History". A booking has to
     * land in one of them: a filter set that can drop a live appointment through the gaps is how
     * a row that exists becomes a row nobody can find.
     */
    @Test
    void everyLiveBookingAppearsUnderOneOfTheDoctorsViews() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        book("/hospital", receptionToken,
                bookingBody(doctor.getId(), tomorrow, "11:00", phone()));
        Long tomorrowId = appointments.findAll().stream()
                .filter(a -> hospital.getId().equals(a.getHospitalId()))
                .findFirst().orElseThrow().getId();

        boolean inToday = containsAppointment(
                pageContent("/hospital/appointments/my-appointments?view=today&page=0&size=50", doctorToken), tomorrowId);
        boolean inUpcoming = containsAppointment(
                pageContent("/hospital/appointments/my-appointments?view=upcoming&page=0&size=50", doctorToken), tomorrowId);
        boolean inHistory = containsAppointment(
                pageContent("/hospital/appointments/my-appointments?view=history&page=0&size=50", doctorToken), tomorrowId);

        assertThat(inToday || inUpcoming || inHistory)
                .as("a booking for tomorrow must be reachable from some view, not fall through all of them")
                .isTrue();
        assertThat(inUpcoming).as("and specifically it belongs under Upcoming").isTrue();
    }

    /** A booking for today must show under Today, and under Upcoming, which means today onwards. */
    @Test
    void aBookingMadeForTodayAppearsUnderTodayAndUpcoming() {
        LocalDate today = LocalDate.now();
        book("/hospital", receptionToken,
                bookingBody(doctor.getId(), today, "23:30", phone()));
        Long todayId = appointments.findAll().stream()
                .filter(a -> hospital.getId().equals(a.getHospitalId()))
                .findFirst().orElseThrow().getId();

        assertThat(containsAppointment(
                pageContent("/hospital/appointments/my-appointments?view=today&page=0&size=50", doctorToken), todayId))
                .as("today's booking under Today").isTrue();
        assertThat(containsAppointment(
                pageContent("/hospital/appointments/my-appointments?view=upcoming&page=0&size=50", doctorToken), todayId))
                .as("a patient arriving later today is still upcoming work").isTrue();
    }

    /** 24-hour operation: the server must accept a booking outside 09:00-17:00. */
    @Test
    void appointmentsCanBeBookedAtAnyHourOfTheDay() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        for (String time : List.of("00:00", "03:30", "13:00", "22:00", "23:30")) {
            ResponseEntity<String> res = book("/hospital", receptionToken,
                    bookingBody(doctor.getId(), tomorrow, time, phone()));
            assertThat(res.getStatusCode().value()).as("booking at %s: %s", time, res.getBody()).isEqualTo(200);
        }
        assertThat(appointments.findAll().stream()
                .filter(a -> hospital.getId().equals(a.getHospitalId()))
                .count()).isEqualTo(5);
    }

    /** Double-booking one doctor at one time is still refused -- validation is not removed. */
    @Test
    void theSameSlotCannotBeBookedTwiceForOneDoctor() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        assertThat(book("/hospital", receptionToken,
                bookingBody(doctor.getId(), tomorrow, "02:00", phone()))
                .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> clash = book("/hospital", receptionToken,
                bookingBody(doctor.getId(), tomorrow, "02:00", phone()));
        assertThat(clash.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(clash.getBody()).contains("already booked");
    }

    /** The same booking and the same visibility, for a clinic tenant on its own aliases. */
    @Test
    void aClinicBookingIsVisibleToTheClinicAndItsDoctor() {
        Hospital clinic = tenant("CLINIC");
        String clinicDocEmail = "clinicdoc." + uniq() + "@appt.test";
        Doctor clinicDoctor = seedDoctor(clinic, clinicDocEmail);
        String clinicReception = tokenFor(staff(clinic, "RECEPTIONIST", "crec." + uniq() + "@appt.test"), "CLINIC");
        String clinicDoctorToken = tokenFor(staff(clinic, "DOCTOR", clinicDocEmail), "CLINIC");

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        assertThat(book("/clinic", clinicReception,
                bookingBody(clinicDoctor.getId(), tomorrow, "20:00", phone()))
                .getStatusCode().value()).isEqualTo(200);

        Long id = appointments.findAll().stream()
                .filter(a -> clinic.getId().equals(a.getHospitalId()))
                .findFirst().orElseThrow().getId();

        assertThat(containsAppointment(pageContent("/clinic/appointments?page=0&size=50", clinicReception), id)).isTrue();
        assertThat(containsAppointment(
                pageContent("/clinic/appointments/my-appointments?page=0&size=50", clinicDoctorToken), id)).isTrue();
    }

    /** Editing the time keeps the appointment visible, at the new time. */
    @Test
    void editingTheTimeKeepsTheAppointmentAndPersists() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        book("/hospital", receptionToken,
                bookingBody(doctor.getId(), tomorrow, "09:00", phone()));
        Appointment row = appointments.findAll().stream()
                .filter(a -> hospital.getId().equals(a.getHospitalId()))
                .findFirst().orElseThrow();

        String update = "{\"doctorId\":" + doctor.getId()
                + ",\"patientId\":" + row.getPatientId()
                + ",\"appointmentDate\":\"" + tomorrow + "\""
                + ",\"appointmentTime\":\"21:15\"}";
        ResponseEntity<String> edited = rest.exchange("/hospital/appointments/" + row.getId(),
                HttpMethod.PUT, new HttpEntity<>(update, headers(receptionToken)), String.class);
        assertThat(edited.getStatusCode().value()).as("%s", edited.getBody()).isEqualTo(200);

        assertThat(appointments.findById(row.getId()).orElseThrow().getAppointmentTime())
                .hasToString("21:15");
        assertThat(containsAppointment(
                pageContent("/hospital/appointments/my-appointments?page=0&size=50", doctorToken), row.getId()))
                .as("still the doctor's appointment after the change").isTrue();
    }

    /** Another facility's appointments are never reachable. */
    @Test
    void appointmentsDoNotCrossTenants() {
        Hospital other = tenant("HOSPITAL");
        String otherReception = tokenFor(staff(other, "RECEPTIONIST", "orec." + uniq() + "@appt.test"), "HOSPITAL");

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        book("/hospital", receptionToken,
                bookingBody(doctor.getId(), tomorrow, "12:00", phone()));
        Long mine = appointments.findAll().stream()
                .filter(a -> hospital.getId().equals(a.getHospitalId()))
                .findFirst().orElseThrow().getId();

        assertThat(containsAppointment(pageContent("/hospital/appointments?page=0&size=50", otherReception), mine))
                .as("another facility must not see this booking").isFalse();
        assertThat(rest.exchange("/hospital/appointments/" + mine, HttpMethod.GET,
                        new HttpEntity<>(headers(otherReception)), String.class)
                .getStatusCode().is2xxSuccessful()).isFalse();
    }
}

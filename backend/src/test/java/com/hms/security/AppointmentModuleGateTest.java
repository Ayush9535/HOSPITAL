package com.hms.security;

import com.hms.controller.hospital.AppointmentController;
import com.hms.entity.Hospital;
import com.hms.entity.HospitalType;
import com.hms.repository.HospitalRepository;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Appointments are an optional tenant capability, and the rule for a tenant without them is
 * "gate writes, preserve historical reads".
 *
 * <p>This drives {@link ModuleAccessAspect} against the <b>real</b> {@link AppointmentController}
 * rather than a stand-in, and enumerates its handlers by reflection instead of naming the four
 * that exist today. That is the point: a fifth mutation added later without
 * {@code @RequireModule("APPOINTMENTS")} fails {@link #everyMutationIsGated()} the moment it is
 * written, which is the only way "do not leave a write endpoint unprotected" survives contact
 * with the next person to touch this controller.
 *
 * <p>The reads are asserted just as deliberately. A hospital that used appointments before the
 * module was withdrawn must still be able to open a past appointment, a patient's history and the
 * bill that references it — and, just as importantly, {@code /stats}, {@code /today} and
 * {@code /my-appointments} are called by three dashboards while they load unrelated tabs. The
 * class-level gate this replaced 403'd all of those.
 */
class AppointmentModuleGateTest {

    private final HospitalRepository hospitalRepository = mock(HospitalRepository.class);
    private final ModuleAccessAspect aspect = new ModuleAccessAspect();

    /** A tenant that walks in only: every module except APPOINTMENTS. */
    private static final List<String> WITHOUT_APPOINTMENTS = List.of("OPD", "IPD", "BILLING");
    private static final List<String> WITH_APPOINTMENTS = List.of("OPD", "IPD", "BILLING", "APPOINTMENTS");

    AppointmentModuleGateTest() {
        ReflectionTestUtils.setField(aspect, "hospitalRepository", hospitalRepository);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── harness ───────────────────────────────────────────────────────────────

    private void tenantHolds(List<String> modules) {
        Hospital hospital = new Hospital();
        hospital.setModules(new ArrayList<>(modules));
        when(hospitalRepository.findById(anyLong())).thenReturn(Optional.of(hospital));

        UserAuthenticationDetails details =
                new UserAuthenticationDetails(1L, "HOSPITAL_ADMIN", 7L, new ArrayList<>(modules));
        details.setHospitalType(HospitalType.HOSPITAL.name());
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("admin@hospital.test", null, List.of());
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private JoinPoint joinPointFor(Method method) {
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(new AppointmentController());
        return joinPoint;
    }

    private static boolean isMutation(Method m) {
        return m.isAnnotationPresent(PostMapping.class)
                || m.isAnnotationPresent(PutMapping.class)
                || m.isAnnotationPresent(DeleteMapping.class)
                || m.isAnnotationPresent(PatchMapping.class);
    }

    private static List<Method> handlers() {
        return java.util.Arrays.stream(AppointmentController.class.getDeclaredMethods())
                .filter(m -> isMutation(m) || m.isAnnotationPresent(GetMapping.class))
                .sorted(java.util.Comparator.comparing(Method::getName))
                .toList();
    }

    // ── the fence ─────────────────────────────────────────────────────────────

    /** Sanity: reflection actually found the controller's handlers. */
    @Test
    void controllerExposesBothReadsAndMutations() {
        List<Method> all = handlers();
        assertThat(all.stream().filter(AppointmentModuleGateTest::isMutation))
                .as("AppointmentController must still expose mutations")
                .isNotEmpty();
        assertThat(all.stream().filter(m -> !isMutation(m)))
                .as("AppointmentController must still expose reads")
                .isNotEmpty();
    }

    @Test
    void everyMutationIsGated() {
        tenantHolds(WITHOUT_APPOINTMENTS);

        for (Method m : handlers()) {
            if (!isMutation(m)) {
                continue;
            }
            assertThatThrownBy(() -> aspect.checkModuleAccess(joinPointFor(m)))
                    .as("%s mutates appointments, so it must carry @RequireModule(\"APPOINTMENTS\") "
                            + "and 403 for a tenant whose plan does not include the module", m.getName())
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("APPOINTMENTS");
        }
    }

    @Test
    void everyReadStaysOpen_soHistorySurvivesTheModuleBeingWithdrawn() {
        tenantHolds(WITHOUT_APPOINTMENTS);

        for (Method m : handlers()) {
            if (isMutation(m)) {
                continue;
            }
            assertThatCode(() -> aspect.checkModuleAccess(joinPointFor(m)))
                    .as("%s only reads. Historical appointments, and the dashboard calls that "
                            + "load alongside unrelated tabs, must survive the module being "
                            + "withdrawn", m.getName())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void everythingWorks_whenTheTenantHoldsTheModule() {
        tenantHolds(WITH_APPOINTMENTS);

        for (Method m : handlers()) {
            assertThatCode(() -> aspect.checkModuleAccess(joinPointFor(m)))
                    .as("%s must work normally for a tenant that bought APPOINTMENTS", m.getName())
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Named coverage of the four mutations that exist today, so a regression reads as
     * "create is no longer gated" rather than as an anonymous loop failure.
     */
    @Test
    void thefourKnownMutationsAreGatedByName() throws Exception {
        tenantHolds(WITHOUT_APPOINTMENTS);

        for (String name : List.of("createAppointment", "updateAppointment",
                "updateAppointmentStatus", "deleteAppointment")) {
            Method m = java.util.Arrays.stream(AppointmentController.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("handler vanished: " + name));

            assertThatThrownBy(() -> aspect.checkModuleAccess(joinPointFor(m)))
                    .as("%s must be blocked without the APPOINTMENTS module", name)
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    /**
     * The withdrawal must bite immediately. ModuleAccessAspect reads the live hospital row rather
     * than the caller's JWT, so a receptionist already signed in when the Super Admin drops
     * APPOINTMENTS cannot keep booking on a stale token.
     */
    @Test
    void moduleWithdrawnMidSession_blocksCreation_onTheNextRequest() throws Exception {
        Hospital live = new Hospital();
        live.setModules(new ArrayList<>(WITHOUT_APPOINTMENTS));      // plan: module dropped
        when(hospitalRepository.findById(anyLong())).thenReturn(Optional.of(live));

        UserAuthenticationDetails stale =                            // token: still claims it
                new UserAuthenticationDetails(1L, "RECEPTIONIST", 7L, new ArrayList<>(WITH_APPOINTMENTS));
        stale.setHospitalType(HospitalType.HOSPITAL.name());
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("front@hospital.test", null, List.of());
        auth.setDetails(stale);
        SecurityContextHolder.getContext().setAuthentication(auth);

        Method create = AppointmentController.class.getMethod("createAppointment", com.hms.entity.Appointment.class);
        assertThatThrownBy(() -> aspect.checkModuleAccess(joinPointFor(create)))
                .isInstanceOf(AccessDeniedException.class);
    }
}

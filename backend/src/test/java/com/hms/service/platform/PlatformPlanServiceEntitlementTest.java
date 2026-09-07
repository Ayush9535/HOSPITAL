package com.hms.service.platform;

import com.hms.dto.CreatePlanRequest;
import com.hms.entity.Hospital;
import com.hms.entity.HospitalPlanSubscription;
import com.hms.entity.HospitalType;
import com.hms.entity.Plan;
import com.hms.repository.AuditLogRepository;
import com.hms.repository.HospitalPlanSubscriptionRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.HospitalSettingRepository;
import com.hms.repository.PlanRepository;
import com.hms.security.HospitalWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformPlanServiceEntitlementTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void creatingAnAppointmentsPlanPersistsItsImpliedOpdModule() {
        PlanRepository plans = mock(PlanRepository.class);
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        when(plans.save(any(Plan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlatformPlanService service = new PlatformPlanService();
        ReflectionTestUtils.setField(service, "planRepository", plans);
        ReflectionTestUtils.setField(service, "auditLogRepository", auditLogs);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@hospital.test", null));

        CreatePlanRequest request = new CreatePlanRequest();
        request.setName("Appointments");
        request.setType("HOSPITAL");
        request.setMonthlyPrice(BigDecimal.ONE);
        request.setYearlyPrice(BigDecimal.TEN);
        request.setModules(List.of("APPOINTMENTS"));

        Plan plan = service.createPlan(request);

        assertThat(plan.getModules()).containsExactlyInAnyOrder("APPOINTMENTS", "OPD");
    }

    @Test
    void updatingAnAppointmentsPlanPersistsItsImpliedOpdModule() {
        PlanRepository plans = mock(PlanRepository.class);
        HospitalPlanSubscriptionRepository subscriptions = mock(HospitalPlanSubscriptionRepository.class);
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        Plan existing = new Plan();
        existing.setPublicId("appointments-plan");
        existing.setName("Starter");
        existing.setType(HospitalType.HOSPITAL);
        existing.setModules(new java.util.ArrayList<>());
        when(plans.findByPublicId("appointments-plan")).thenReturn(Optional.of(existing));
        when(plans.save(any(Plan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(subscriptions.findByPlan_IdAndIsCurrentTrue(null)).thenReturn(List.of());

        PlatformPlanService service = new PlatformPlanService();
        ReflectionTestUtils.setField(service, "planRepository", plans);
        ReflectionTestUtils.setField(service, "subscriptionRepository", subscriptions);
        ReflectionTestUtils.setField(service, "auditLogRepository", auditLogs);
        authenticate();

        Plan updated = service.updatePlan("appointments-plan", appointmentsRequest());

        assertThat(updated.getModules()).containsExactlyInAnyOrder("APPOINTMENTS", "OPD");
    }

    @Test
    void propagatingAnUpdatedAppointmentsPlanAddsOpdToEachSubscriber() {
        PlanRepository plans = mock(PlanRepository.class);
        HospitalPlanSubscriptionRepository subscriptions = mock(HospitalPlanSubscriptionRepository.class);
        HospitalRepository hospitals = mock(HospitalRepository.class);
        HospitalSettingRepository settings = mock(HospitalSettingRepository.class);
        HospitalWebSocketHandler socket = mock(HospitalWebSocketHandler.class);
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        Plan existing = new Plan();
        existing.setId(41L);
        existing.setPublicId("appointments-plan");
        existing.setName("Starter");
        existing.setType(HospitalType.HOSPITAL);
        existing.setModules(new java.util.ArrayList<>());
        HospitalPlanSubscription subscription = new HospitalPlanSubscription();
        subscription.setHospitalId(7L);
        Hospital hospital = new Hospital();
        hospital.setId(7L);

        when(plans.findByPublicId("appointments-plan")).thenReturn(Optional.of(existing));
        when(plans.save(any(Plan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(subscriptions.findByPlan_IdAndIsCurrentTrue(41L)).thenReturn(List.of(subscription));
        when(hospitals.findById(7L)).thenReturn(Optional.of(hospital));
        when(hospitals.save(any(Hospital.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(settings.findByHospital(hospital)).thenReturn(Optional.empty());

        PlatformPlanService service = new PlatformPlanService();
        ReflectionTestUtils.setField(service, "planRepository", plans);
        ReflectionTestUtils.setField(service, "subscriptionRepository", subscriptions);
        ReflectionTestUtils.setField(service, "hospitalRepository", hospitals);
        ReflectionTestUtils.setField(service, "hospitalSettingRepository", settings);
        ReflectionTestUtils.setField(service, "webSocketHandler", socket);
        ReflectionTestUtils.setField(service, "auditLogRepository", auditLogs);
        authenticate();

        service.updatePlan("appointments-plan", appointmentsRequest());

        assertThat(hospital.getModules()).containsExactlyInAnyOrder("APPOINTMENTS", "OPD");
    }

    @Test
    void applyingALegacyAppointmentsPlanAddsItsRequiredOpdModule() {
        Plan legacyPlan = new Plan();
        legacyPlan.setType(HospitalType.HOSPITAL);
        legacyPlan.setModules(List.of("APPOINTMENTS"));
        Hospital hospital = new Hospital();
        hospital.setId(7L);

        HospitalRepository hospitals = mock(HospitalRepository.class);
        HospitalSettingRepository settings = mock(HospitalSettingRepository.class);
        when(hospitals.save(any(Hospital.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(settings.findByHospital(hospital)).thenReturn(Optional.empty());

        PlatformPlanService service = new PlatformPlanService();
        ReflectionTestUtils.setField(service, "hospitalRepository", hospitals);
        ReflectionTestUtils.setField(service, "hospitalSettingRepository", settings);
        ReflectionTestUtils.setField(service, "webSocketHandler", mock(HospitalWebSocketHandler.class));

        ReflectionTestUtils.invokeMethod(service, "applyPlanToHospital", hospital, legacyPlan);

        assertThat(hospital.getModules()).containsExactlyInAnyOrder("APPOINTMENTS", "OPD");
    }

    private static CreatePlanRequest appointmentsRequest() {
        CreatePlanRequest request = new CreatePlanRequest();
        request.setName("Appointments");
        request.setType("HOSPITAL");
        request.setMonthlyPrice(BigDecimal.ONE);
        request.setYearlyPrice(BigDecimal.TEN);
        request.setModules(List.of("APPOINTMENTS"));
        return request;
    }

    private static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@hospital.test", null));
    }
}

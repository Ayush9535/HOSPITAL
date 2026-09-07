package com.hms.service.platform;

import com.hms.dto.AssignPlanRequest;
import com.hms.dto.CreatePlanRequest;
import com.hms.entity.*;
import com.hms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatformPlanServiceTest {

    @Mock PlanRepository planRepository;
    @Mock HospitalRepository hospitalRepository;
    @Mock HospitalPlanSubscriptionRepository subscriptionRepository;
    @Mock HospitalSettingRepository hospitalSettingRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock UserRepository userRepository;
    @Mock com.hms.security.HospitalWebSocketHandler webSocketHandler;

    @InjectMocks PlatformPlanService service;

    @BeforeEach
    void mockSecurity() {
        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getName()).thenReturn("superadmin@hms.com");
        SecurityContext ctx = mock(SecurityContext.class);
        lenient().when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    @Test
    void createPlan_savesAndReturnsPlan() {
        CreatePlanRequest req = new CreatePlanRequest();
        req.setName("Clinic Essential");
        req.setType("CLINIC");
        req.setMonthlyPrice(new BigDecimal("999.00"));
        req.setYearlyPrice(new BigDecimal("9999.00"));
        req.setModules(List.of("OPD", "BILLING"));
        req.setFeatures(List.of("OPD Management", "GST Billing"));
        req.setInClinic(false);

        Plan savedPlan = new Plan();
        savedPlan.setId(1L);
        savedPlan.setName("Clinic Essential");
        savedPlan.setType(HospitalType.CLINIC);
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);

        Plan result = service.createPlan(req);

        assertThat(result.getName()).isEqualTo("Clinic Essential");
        assertThat(result.getType()).isEqualTo(HospitalType.CLINIC);
        verify(planRepository).save(any(Plan.class));
    }

    @Test
    void createPlan_rejectsInvalidModulesBeforePersistence() {
        CreatePlanRequest req = new CreatePlanRequest();
        req.setName("Invalid");
        req.setType("HOSPITAL");
        req.setMonthlyPrice(BigDecimal.ONE);
        req.setYearlyPrice(BigDecimal.TEN);
        req.setModules(List.of("NOT_A_REAL_MODULE"));

        assertThatThrownBy(() -> service.createPlan(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown plan module");
        verify(planRepository, never()).save(any());
    }

    @Test
    void createPlan_addsPharmacyBaseOnlyAfterValidatingTier() {
        CreatePlanRequest req = new CreatePlanRequest();
        req.setName("Multi");
        req.setType("PHARMACY");
        req.setMonthlyPrice(BigDecimal.ONE);
        req.setYearlyPrice(BigDecimal.TEN);
        req.setModules(List.of("MULTI_PHARMACY"));
        req.setMaxOutlets(3);
        when(planRepository.save(any(Plan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plan result = service.createPlan(req);

        assertThat(result.getModules()).containsExactly("MULTI_PHARMACY", "PHARMACY");
        assertThat(result.getMultiOutlet()).isTrue();
        assertThat(result.getMaxOutlets()).isEqualTo(3);
    }

    @Test
    void deletePlan_throwsWhenActiveSubscribers() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setName("Clinic Essential");
        when(planRepository.findByPublicId("pub-123")).thenReturn(Optional.of(plan));
        when(subscriptionRepository.countByPlan_IdAndIsCurrentTrue(1L)).thenReturn(3L);

        assertThatThrownBy(() -> service.deletePlan("pub-123"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("3 active entities");
    }

    @Test
    void deletePlan_succeedsWhenNoSubscribers() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setName("Old Plan");
        when(planRepository.findByPublicId("pub-456")).thenReturn(Optional.of(plan));
        when(subscriptionRepository.countByPlan_IdAndIsCurrentTrue(1L)).thenReturn(0L);

        service.deletePlan("pub-456");

        verify(planRepository).delete(plan);
    }

    @Test
    void assignPlan_throwsOnTypeMismatch() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setType(HospitalType.CLINIC);
        when(planRepository.findByPublicId("plan-pub")).thenReturn(Optional.of(plan));

        Hospital hospital = new Hospital();
        hospital.setId(10L);
        hospital.setType(HospitalType.HOSPITAL);
        when(hospitalRepository.findByPublicId("hosp-pub")).thenReturn(Optional.of(hospital));

        AssignPlanRequest req = new AssignPlanRequest();
        req.setHospitalPublicId("hosp-pub");
        req.setBillingPeriod("MONTHLY");

        assertThatThrownBy(() -> service.assignPlan("plan-pub", req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("does not match entity type");
    }

    @Test
    void assignPlan_setsModulesAndCreatesSubscription() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setType(HospitalType.HOSPITAL);
        plan.setModules(new ArrayList<>(List.of("OPD", "IPD", "BILLING")));
        plan.setInClinic(false);
        when(planRepository.findByPublicId("plan-pub")).thenReturn(Optional.of(plan));

        Hospital hospital = new Hospital();
        hospital.setId(10L);
        hospital.setType(HospitalType.HOSPITAL);
        hospital.setName("Test Hospital");
        when(hospitalRepository.findByPublicId("hosp-pub")).thenReturn(Optional.of(hospital));
        when(hospitalRepository.save(any())).thenReturn(hospital);
        when(hospitalSettingRepository.findByHospital(any())).thenReturn(Optional.empty());

        HospitalPlanSubscription savedSub = new HospitalPlanSubscription();
        savedSub.setId(1L);
        when(subscriptionRepository.save(any())).thenReturn(savedSub);

        AssignPlanRequest req = new AssignPlanRequest();
        req.setHospitalPublicId("hosp-pub");
        req.setBillingPeriod("MONTHLY");

        HospitalPlanSubscription result = service.assignPlan("plan-pub", req);

        assertThat(result.getId()).isEqualTo(1L);
        verify(subscriptionRepository).deactivateCurrentSubscription(10L);
        verify(hospitalRepository).save(argThat(h -> h.getModules().containsAll(List.of("OPD", "IPD", "BILLING"))));
    }
}

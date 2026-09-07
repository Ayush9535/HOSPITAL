package com.hms.security;

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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FacilityAccessAspectTest {
    private final HospitalRepository hospitals = mock(HospitalRepository.class);
    private final FacilityAccessAspect aspect = new FacilityAccessAspect();

    FacilityAccessAspectTest() {
        ReflectionTestUtils.setField(aspect, "hospitalRepository", hospitals);
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private void authenticate(HospitalType type, List<String> modules) {
        Hospital hospital = new Hospital(); hospital.setType(type); hospital.setModules(modules);
        when(hospitals.findById(anyLong())).thenReturn(Optional.of(hospital));
        UserAuthenticationDetails details = new UserAuthenticationDetails(1L, "HOSPITAL_ADMIN", 7L, modules);
        details.setHospitalType(HospitalType.HOSPITAL.name()); // A forged/stale token must not decide access.
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("u", null, List.of());
        auth.setDetails(details); SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private JoinPoint controller(String name) {
        MethodSignature signature = mock(MethodSignature.class);
        Class<?> declaring = switch (name) {
            case "IpdAdmissionController" -> com.hms.controller.hospital.IpdAdmissionController.class;
            case "WardController" -> com.hms.controller.hospital.WardController.class;
            case "BedController" -> com.hms.controller.hospital.BedController.class;
            case "VitalsController" -> com.hms.controller.hospital.VitalsController.class;
            case "SurgeryController" -> com.hms.controller.hospital.SurgeryController.class;
            case "OpdController" -> com.hms.controller.hospital.OpdController.class;
            case "AppointmentController" -> com.hms.controller.hospital.AppointmentController.class;
            case "BillingController" -> com.hms.controller.hospital.BillingController.class;
            case "PharmacySaleController" -> com.hms.controller.pharmacy.PharmacySaleController.class;
            default -> throw new IllegalArgumentException(name);
        };
        when(signature.getDeclaringType()).thenReturn(declaring);
        JoinPoint joinPoint = mock(JoinPoint.class); when(joinPoint.getSignature()).thenReturn(signature);
        return joinPoint;
    }

    @Test void clinic_isBlockedBeforeHospitalOnlyControllers() {
        authenticate(HospitalType.CLINIC, List.of("OPD", "BILLING"));
        for (String c : List.of("IpdAdmissionController", "WardController", "BedController", "VitalsController", "SurgeryController")) {
            assertThatThrownBy(() -> aspect.checkFacilityAccess(controller(c))).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test void clinic_keepsApprovedOutpatientControllers() {
        authenticate(HospitalType.CLINIC, List.of("OPD", "BILLING", "APPOINTMENTS"));
        for (String c : List.of("OpdController", "AppointmentController", "BillingController")) {
            assertThatCode(() -> aspect.checkFacilityAccess(controller(c))).doesNotThrowAnyException();
        }
    }

    @Test void pharmacy_isBlockedFromHospitalAndOutpatientControllers() {
        authenticate(HospitalType.PHARMACY, List.of("SINGLE_PHARMACY"));
        for (String c : List.of("IpdAdmissionController", "WardController", "VitalsController", "SurgeryController", "OpdController", "AppointmentController")) {
            assertThatThrownBy(() -> aspect.checkFacilityAccess(controller(c))).isInstanceOf(AccessDeniedException.class);
        }
        assertThatCode(() -> aspect.checkFacilityAccess(controller("PharmacySaleController"))).doesNotThrowAnyException();
    }

    @Test void hospital_behavior_isUnchanged() {
        authenticate(HospitalType.HOSPITAL, List.of());
        for (String c : List.of("IpdAdmissionController", "WardController", "BedController", "VitalsController", "SurgeryController", "OpdController", "PharmacySaleController")) {
            assertThatCode(() -> aspect.checkFacilityAccess(controller(c))).doesNotThrowAnyException();
        }
    }
}

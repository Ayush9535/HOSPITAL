package com.hms.controller.hospital;

import com.hms.security.JwtUtil;
import com.hms.service.hospital.OpdService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P0-3: pending IPD requests are counted server-side.
 *
 * <p>The reception dashboard used to fetch one 1000-row page of OPDs and filter it in the browser,
 * so a hospital with more OPDs than that silently under-reported pending admissions. These tests
 * pin the endpoint's contract and its role gate; the repository query itself proves the tenant
 * through the owning patient and is covered by OpdPendingIpdRequestsQueryTest.
 */
@WebMvcTest(OpdController.class)
@Import(OpdControllerIpdRequestsTest.MethodSecurityTestConfig.class)
class OpdControllerIpdRequestsTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {}

    @Autowired private MockMvc mockMvc;

    @MockBean private OpdService opdService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private com.hms.security.SecurityContextHelper securityContextHelper;
    @MockBean private com.hms.service.PdfService pdfService;
    @MockBean private com.hms.repository.HospitalSettingRepository hospitalSettingRepository;
    @MockBean private com.hms.repository.DoctorRepository doctorRepository;
    @MockBean private com.hms.repository.MedicalRecordRepository medicalRecordRepository;
    @MockBean private com.hms.repository.HospitalRepository hospitalRepository;
    @MockBean private com.hms.repository.LabOrderRepository labOrderRepository;
    @MockBean private com.hms.repository.BillingRepository billingRepository;
    @MockBean private com.hms.repository.PrescriptionRepository prescriptionRepository;

    // OpdController now claims an idempotency key before registering, so this slice needs the
    // collaborators that claim depends on.
    @MockBean
    private com.hms.service.hospital.OpdIdempotencyService opdIdempotencyService;

    @MockBean
    private com.hms.repository.OpdRepository opdIdempotencyOpdRepository;
    @MockBean private com.hms.repository.UserRepository userRepository;
    @MockBean private com.hms.service.hospital.PatientService patientService;

    /** A count well past the old 1000-row client-side page still comes back intact. */
    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void countIsServedFromTheServerAndSurvivesLargeTenants() throws Exception {
        when(opdService.getPendingIpdRequestCount()).thenReturn(1207L);

        mockMvc.perform(get("/hospital/opd/ipd-requests/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1207));
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void adminMayReadTheCount() throws Exception {
        when(opdService.getPendingIpdRequestCount()).thenReturn(3L);

        mockMvc.perform(get("/hospital/opd/ipd-requests/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void listIsPagedAndServedFromTheServer() throws Exception {
        when(opdService.getPendingIpdRequests(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/hospital/opd/ipd-requests?page=0&size=25"))
                .andExpect(status().isOk());
        verify(opdService).getPendingIpdRequests(any(Pageable.class));
    }

    /** Neither endpoint is part of a doctor's or pharmacist's job. */
    @Test
    @WithMockUser(roles = "DOCTOR")
    void doctorCannotReadPendingIpdRequests() throws Exception {
        mockMvc.perform(get("/hospital/opd/ipd-requests/count"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/hospital/opd/ipd-requests"))
                .andExpect(status().isForbidden());
        verify(opdService, never()).getPendingIpdRequestCount();
    }

    @Test
    @WithMockUser(roles = "PHARMACIST")
    void pharmacistCannotReadPendingIpdRequests() throws Exception {
        mockMvc.perform(get("/hospital/opd/ipd-requests/count"))
                .andExpect(status().isForbidden());
        verify(opdService, never()).getPendingIpdRequestCount();
    }

    /**
     * The literal path must win over the sibling {@code @GetMapping("/{id}")}, or the count request
     * would be interpreted as a lookup for an OPD whose id is "ipd-requests".
     */
    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void theLiteralPathIsNotSwallowedByTheByIdMapping() throws Exception {
        when(opdService.getPendingIpdRequestCount()).thenReturn(0L);

        mockMvc.perform(get("/hospital/opd/ipd-requests/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        verify(opdService).getPendingIpdRequestCount();
    }
}

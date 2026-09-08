package com.hms.controller.hospital;

import com.hms.entity.Hospital;
import com.hms.entity.HospitalSetting;
import com.hms.repository.*;
import com.hms.security.SecurityContextHelper;
import com.hms.security.JwtUtil;
import com.hms.service.PdfService;
import com.hms.service.AuditLogService;
import com.hms.service.hospital.BillingService;
import com.hms.service.hospital.PatientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BillingController.class)
public class BillingControllerTest {

    // R1b: JwtAuthenticationFilter now revalidates the session against these repositories on
    // every request. A @WebMvcTest slice does not start JPA, so they have to be supplied here
    // for the context to load. Mocked, not stubbed -- these tests are about controllers, and
    // the real revocation behaviour is covered by SessionRevocationTest.
    @MockBean
    private com.hms.repository.UserRepository r1bUserRepository;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private AuditLogService auditLogService;

    @MockBean
    private BillingService billingService;

    @MockBean
    private BillingPaymentRepository billingPaymentRepository;

    @MockBean
    private HospitalSettingRepository hospitalSettingRepository;

    @MockBean
    private PdfService pdfService;

    @MockBean
    private HospitalRepository hospitalRepository;

    @MockBean
    private PatientService patientService;

    @MockBean
    private BillingRepository billingRepository;

    @MockBean
    private BillingItemRepository billingItemRepository;

    @MockBean
    private IpdAdmissionRepository ipdAdmissionRepository;

    @MockBean
    private WardRepository wardRepository;

    @MockBean
    private SecurityContextHelper securityHelper;

    @MockBean
    private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    @MockBean
    private com.hms.repository.BillingMedicineRepository billingMedicineRepository;

    private HospitalSetting settings;

    @BeforeEach
    void setUp() {
        settings = new HospitalSetting();
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        settings.setHospital(hospital);
        
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(hospitalSettingRepository.findByHospital_Id(1L)).thenReturn(Optional.of(settings));

        org.springframework.data.domain.Page<com.hms.entity.Billing> emptyPage = new org.springframework.data.domain.PageImpl<>(Collections.emptyList());
        when(billingService.getAllBills(null, null, org.springframework.data.domain.PageRequest.of(0, 10, org.springframework.data.domain.Sort.by("createdAt").descending())))
                .thenReturn(emptyPage);
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    public void testDoctorAccess_WhenBillingHandlerIsDoctor_Allowed() throws Exception {
        settings.setBillingHandler("DOCTOR");
        when(securityHelper.getCurrentUserRole()).thenReturn("ROLE_DOCTOR");

        mockMvc.perform(get("/hospital/billing")
                .param("page", "0")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    public void testDoctorAccess_WhenBillingHandlerIsReceptionist_Forbidden() throws Exception {
        settings.setBillingHandler("RECEPTIONIST");
        settings.setReceptionMode("HAS_RECEPTIONIST");
        when(securityHelper.getCurrentUserRole()).thenReturn("ROLE_DOCTOR");

        mockMvc.perform(get("/hospital/billing")
                .param("page", "0")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    public void testDoctorAccess_WhenBillingHandlerIsReceptionistButReceptionModeIsSolo_Allowed() throws Exception {
        settings.setBillingHandler("RECEPTIONIST");
        settings.setReceptionMode("SOLO");
        when(securityHelper.getCurrentUserRole()).thenReturn("ROLE_DOCTOR");

        mockMvc.perform(get("/hospital/billing")
                .param("page", "0")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    public void testReceptionistAccess_WhenBillingHandlerIsReceptionist_Allowed() throws Exception {
        settings.setBillingHandler("RECEPTIONIST");
        settings.setReceptionMode("HAS_RECEPTIONIST");
        when(securityHelper.getCurrentUserRole()).thenReturn("ROLE_RECEPTIONIST");

        mockMvc.perform(get("/hospital/billing")
                .param("page", "0")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    public void testReceptionistAccess_WhenBillingHandlerIsDoctor_Forbidden() throws Exception {
        settings.setBillingHandler("DOCTOR");
        settings.setReceptionMode("HAS_RECEPTIONIST");
        when(securityHelper.getCurrentUserRole()).thenReturn("ROLE_RECEPTIONIST");

        mockMvc.perform(get("/hospital/billing")
                .param("page", "0")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    public void testReceptionistAccess_WhenReceptionModeIsSolo_Forbidden() throws Exception {
        settings.setBillingHandler("RECEPTIONIST");
        settings.setReceptionMode("SOLO");
        when(securityHelper.getCurrentUserRole()).thenReturn("ROLE_RECEPTIONIST");

        mockMvc.perform(get("/hospital/billing")
                .param("page", "0")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    public void testAdminAccess_AlwaysAllowed() throws Exception {
        settings.setBillingHandler("DOCTOR");
        when(securityHelper.getCurrentUserRole()).thenReturn("ROLE_HOSPITAL_ADMIN");

        mockMvc.perform(get("/hospital/billing")
                .param("page", "0")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}

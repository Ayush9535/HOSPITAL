package com.hms.controller.hospital;

import com.hms.entity.HospitalServiceEntity;
import com.hms.repository.InventoryItemRepository;
import com.hms.security.JwtUtil;
import com.hms.service.AuditLogService;
import com.hms.service.hospital.HospitalServiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HospitalServiceController.class)
@Import(HospitalServiceControllerTest.MethodSecurityTestConfig.class)
class HospitalServiceControllerTest {

    // R1b: JwtAuthenticationFilter now revalidates the session against these repositories on
    // every request. A @WebMvcTest slice does not start JPA, so they have to be supplied here
    // for the context to load. Mocked, not stubbed -- these tests are about controllers, and
    // the real revocation behaviour is covered by SessionRevocationTest.
    @MockBean
    private com.hms.repository.UserRepository r1bUserRepository;
    @MockBean
    private com.hms.repository.HospitalRepository r1bHospitalRepository;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {}

    @Autowired private MockMvc mockMvc;

    @MockBean private HospitalServiceService serviceService;
    @MockBean private InventoryItemRepository inventoryItemRepository;
    @MockBean private com.hms.security.SecurityContextHelper securityHelper;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private AuditLogService auditLogService;

    @Test
    @WithMockUser(roles = "DOCTOR")
    void listServices_okForDoctor() throws Exception {
        when(serviceService.listServices()).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/hospital/services").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PHARMACIST")
    void listServices_forbiddenForPharmacist() throws Exception {
        mockMvc.perform(get("/hospital/services").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void createService_okWhenServiceSucceeds() throws Exception {
        HospitalServiceEntity saved = new HospitalServiceEntity();
        saved.setId(1L);
        saved.setName("Dressing");
        saved.setCharge(new BigDecimal("150"));
        when(serviceService.createService(eq("Dressing"), any(), anyList())).thenReturn(saved);
        when(serviceService.getItemNamesForService(1L)).thenReturn(Collections.emptyList());
        when(serviceService.getMasterItemIdsForService(1L)).thenReturn(Collections.emptyList());

        mockMvc.perform(post("/hospital/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dressing\",\"charge\":150,\"masterItemIds\":[2]}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dressing"));
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void createService_badRequestWhenServiceThrows() throws Exception {
        when(serviceService.createService(anyString(), any(), anyList()))
                .thenThrow(new IllegalArgumentException("Service name is required"));
        mockMvc.perform(post("/hospital/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"charge\":150,\"masterItemIds\":[]}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void listMasterItems_okForDoctor() throws Exception {
        when(securityHelper.getCurrentUserDetails()).thenReturn(null);
        when(inventoryItemRepository.findAll()).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/hospital/inventory-master").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void deleteService_okForAdmin() throws Exception {
        mockMvc.perform(delete("/hospital/services/7").with(csrf()))
                .andExpect(status().isOk());
    }
}

package com.hms.controller.hospital;

import com.hms.entity.User;
import com.hms.security.JwtUtil;
import com.hms.service.hospital.OtInchargeService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OtInchargeController.class)
@Import(OtInchargeControllerTest.MethodSecurityTestConfig.class)
class OtInchargeControllerTest {

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

    @MockBean private OtInchargeService otInchargeService;
    @MockBean private JwtUtil jwtUtil;

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void createOtIncharge_ok() throws Exception {
        User ot = new User();
        ot.setName("Test OT Incharge");
        ot.setEmail("otincharge@test.com");
        ot.setRole("OT_INCHARGE");

        when(otInchargeService.createOtIncharge(anyString(), anyString(), anyString())).thenReturn(ot);

        mockMvc.perform(post("/hospital/ot-incharges")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test OT Incharge\",\"email\":\"otincharge@test.com\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test OT Incharge"))
                .andExpect(jsonPath("$.role").value("OT_INCHARGE"));
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void createOtIncharge_forbiddenForDoctor() throws Exception {
        mockMvc.perform(post("/hospital/ot-incharges")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test OT Incharge\",\"email\":\"otincharge@test.com\",\"password\":\"password\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void getAllOtIncharges_ok() throws Exception {
        when(otInchargeService.getAllOtIncharges(any(), any(Pageable.class))).thenReturn(new PageImpl<>(Collections.emptyList()));
        mockMvc.perform(get("/hospital/ot-incharges").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void getOtInchargeById_ok() throws Exception {
        User ot = new User();
        ot.setPublicId("ot-1");
        ot.setName("OT Incharge");

        when(otInchargeService.getOtInchargeByPublicId("ot-1")).thenReturn(ot);

        mockMvc.perform(get("/hospital/ot-incharges/ot-1").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("OT Incharge"));
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void deleteOtIncharge_ok() throws Exception {
        mockMvc.perform(delete("/hospital/ot-incharges/ot-1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void updateOtIncharge_ok() throws Exception {
        User ot = new User();
        ot.setName("Updated Incharge");

        when(otInchargeService.updateOtIncharge(eq("ot-1"), anyString())).thenReturn(ot);

        mockMvc.perform(put("/hospital/ot-incharges/ot-1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Incharge\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Incharge"));
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void resetOtInchargePassword_ok() throws Exception {
        mockMvc.perform(post("/hospital/ot-incharges/ot-1/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"newpassword123\"}"))
                .andExpect(status().isOk());
    }
}

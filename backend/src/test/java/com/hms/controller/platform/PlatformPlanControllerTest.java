package com.hms.controller.platform;

import com.hms.service.platform.PlatformPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformPlanController.class)
@Import(PlatformPlanControllerTest.MethodSecurityTestConfig.class)
class PlatformPlanControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @Autowired private MockMvc mockMvc;
    @MockBean private PlatformPlanService planService;
    @MockBean private com.hms.security.JwtUtil jwtUtil;
    @MockBean private com.hms.repository.UserRepository userRepository;
    @MockBean private com.hms.repository.HospitalRepository hospitalRepository;

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void capabilitiesReturnsOnlyOperatorSelectableEntries() throws Exception {
        mockMvc.perform(get("/platform/plans/capabilities").param("type", "HOSPITAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'OPD')]").exists())
                .andExpect(jsonPath("$[?(@.key == 'PATHOLOGY')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.key == 'PHARMACY_BRANCH')]").doesNotExist());
    }
}

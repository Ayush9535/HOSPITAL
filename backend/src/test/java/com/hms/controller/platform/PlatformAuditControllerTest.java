package com.hms.controller.platform;

import com.hms.entity.AuditLog;
import com.hms.security.JwtUtil;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.service.AuditLogService;
import com.hms.service.hospital.MedicineService;
import com.hms.service.platform.PlatformHospitalService;
import com.hms.security.SecurityContextHelper;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WebMvcTest for PlatformAuditController.
 * BUG-026: adds controller-layer coverage for the /platform/audit-logs endpoint.
 *
 * Verifies:
 *  - SUPER_ADMIN receives audit log list (200 OK).
 *  - Non-admin roles receive 403 Forbidden.
 *  - Empty list is returned as a valid JSON array.
 */
@WebMvcTest(PlatformAuditController.class)
@Import(PlatformAuditControllerTest.MethodSecurityTestConfig.class)
class PlatformAuditControllerTest {

    // R1b: JwtAuthenticationFilter now revalidates the session against these repositories on
    // every request. A @WebMvcTest slice does not start JPA, so they have to be supplied here
    // for the context to load. Mocked, not stubbed -- these tests are about controllers, and
    // the real revocation behaviour is covered by SessionRevocationTest.
    @MockBean
    private com.hms.repository.UserRepository r1bUserRepository;
    @MockBean
    private com.hms.repository.HospitalRepository r1bHospitalRepository;

    // @WebMvcTest does not load SecurityConfig (a plain @Configuration bean), so
    // @PreAuthorize on the controller is never enforced without this. Without it,
    // any authenticated @WithMockUser reaches the handler regardless of role.
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean private PlatformHospitalService hospitalService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private AuditLogService auditLogService;
    @MockBean private SecurityContextHelper securityHelper;
    @MockBean private HospitalWebSocketHandler webSocketHandler;
    @MockBean private MedicineService medicineService;

    // ─── GET /platform/audit-logs ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void getAuditLogs_returnsOkWithListForSuperAdmin() throws Exception {
        AuditLog log = new AuditLog();
        log.setId(1L);
        log.setAction("CREATE_HOSPITAL");
        log.setEntityType("Hospital");
        log.setEntityId("hosp-abc");
        log.setPerformedBy("superadmin@hms.com");
        log.setTimestamp(LocalDateTime.now());

        when(hospitalService.getAuditLogs()).thenReturn(List.of(log));

        mockMvc.perform(get("/platform/audit-logs")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("CREATE_HOSPITAL"))
                .andExpect(jsonPath("$[0].entityType").value("Hospital"));
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void getAuditLogs_returnsEmptyArrayWhenNoLogsExist() throws Exception {
        when(hospitalService.getAuditLogs()).thenReturn(List.of());

        mockMvc.perform(get("/platform/audit-logs")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void getAuditLogs_returnsForbiddenForHospitalAdmin() throws Exception {
        mockMvc.perform(get("/platform/audit-logs")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void getAuditLogs_returnsForbiddenForDoctor() throws Exception {
        mockMvc.perform(get("/platform/audit-logs")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void getAuditLogs_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(get("/platform/audit-logs")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}

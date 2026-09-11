package com.hms.controller.hospital;

import com.hms.entity.ConsultationNotePreset;
import com.hms.security.JwtUtil;
import com.hms.service.AuditLogService;
import com.hms.service.hospital.ConsultationNotePresetService;
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

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(ConsultationNotePresetController.class)
@Import(ConsultationNotePresetControllerTest.MethodSecurityTestConfig.class)
class ConsultationNotePresetControllerTest {

    // R1b: JwtAuthenticationFilter now revalidates the session against these repositories on
    // every request. A @WebMvcTest slice does not start JPA, so they have to be supplied here
    // for the context to load. Mocked, not stubbed -- these tests are about controllers, and
    // the real revocation behaviour is covered by SessionRevocationTest.
    @MockBean
    private com.hms.repository.UserRepository r1bUserRepository;
    @MockBean
    private com.hms.repository.HospitalRepository r1bHospitalRepository;

    // @WebMvcTest does not load SecurityConfig (a plain @Configuration bean), so
    // @PreAuthorize on the controller is never enforced without this.
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean private ConsultationNotePresetService presetService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private AuditLogService auditLogService;
    @MockBean private com.hms.repository.DoctorRepository doctorRepository;

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void listPresets_returnsOkForHospitalAdmin() throws Exception {
        when(presetService.listPresets("TREATMENT_NOTES")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/hospital/consultation-note-presets")
                        .param("fieldType", "TREATMENT_NOTES")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void listPresets_returnsOkForDoctor() throws Exception {
        when(presetService.listPresets("TREATMENT_NOTES")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/hospital/consultation-note-presets")
                        .param("fieldType", "TREATMENT_NOTES")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void listPresets_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(get("/hospital/consultation-note-presets")
                        .param("fieldType", "TREATMENT_NOTES")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void createPreset_returnsOkWhenServiceSucceeds() throws Exception {
        ConsultationNotePreset saved = new ConsultationNotePreset();
        saved.setId(1L);
        saved.setFieldType("TREATMENT_NOTES");
        saved.setText("Avoid oily food");
        saved.setDisplayOrder(0);
        when(presetService.createPreset(eq("TREATMENT_NOTES"), eq("Avoid oily food"), any())).thenReturn(saved);

        mockMvc.perform(post("/hospital/consultation-note-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldType\":\"TREATMENT_NOTES\",\"text\":\"Avoid oily food\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Avoid oily food"));
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void createPreset_returnsBadRequestWhenServiceThrows() throws Exception {
        when(presetService.createPreset(anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("Preset text is required"));

        mockMvc.perform(post("/hospital/consultation-note-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldType\":\"TREATMENT_NOTES\",\"text\":\"\"}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void createPreset_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(post("/hospital/consultation-note-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldType\":\"TREATMENT_NOTES\",\"text\":\"Avoid oily food\"}")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void updatePreset_returnsOkWhenFound() throws Exception {
        ConsultationNotePreset updated = new ConsultationNotePreset();
        updated.setId(5L);
        updated.setText("Updated text");
        when(presetService.updatePreset(eq(5L), eq("Updated text"), any(), any())).thenReturn(updated);

        mockMvc.perform(put("/hospital/consultation-note-presets/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Updated text\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Updated text"));
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void updatePreset_returnsBadRequestWhenNotFound() throws Exception {
        when(presetService.updatePreset(eq(999L), any(), any(), any()))
                .thenThrow(new com.hms.exception.ResourceNotFoundException("Preset not found"));

        mockMvc.perform(put("/hospital/consultation-note-presets/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"X\"}")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void deletePreset_returnsOkWhenSuccessful() throws Exception {
        mockMvc.perform(delete("/hospital/consultation-note-presets/7").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void deletePreset_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(delete("/hospital/consultation-note-presets/7").with(csrf()))
                .andExpect(status().isForbidden());
    }
}

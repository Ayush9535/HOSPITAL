package com.hms.controller.hospital;

import com.hms.dto.PrescriptionPresetItemDTO;
import com.hms.entity.PrescriptionPreset;
import com.hms.security.JwtUtil;
import com.hms.service.AuditLogService;
import com.hms.service.hospital.PrescriptionPresetService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(PrescriptionPresetController.class)
@Import(PrescriptionPresetControllerTest.MethodSecurityTestConfig.class)
class PrescriptionPresetControllerTest {

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
    static class MethodSecurityTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean private PrescriptionPresetService presetService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private AuditLogService auditLogService;
    @MockBean private com.hms.repository.DoctorRepository doctorRepository;

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void listPresets_returnsOkForHospitalAdmin() throws Exception {
        when(presetService.listPresets(any())).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/hospital/prescription-presets").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void listPresets_returnsOkForDoctor() throws Exception {
        when(presetService.listPresets(any())).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/hospital/prescription-presets").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    /** Presets — prescription and in-clinic alike — are clinical, so reception has no access. */
    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void listPresets_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(get("/hospital/prescription-presets").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void createPreset_returnsOkWhenServiceSucceeds() throws Exception {
        PrescriptionPreset saved = new PrescriptionPreset();
        saved.setId(1L);
        saved.setName("Fever Protocol");
        saved.setDisplayOrder(0);
        when(presetService.createPreset(eq("Fever Protocol"), anyList(), any(), any())).thenReturn(saved);
        when(presetService.getItems(1L)).thenReturn(Collections.emptyList());

        mockMvc.perform(post("/hospital/prescription-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Fever Protocol\",\"items\":[{\"medicineName\":\"Paracetamol\",\"dosage\":\"500mg\"}]}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Fever Protocol"));
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void createPreset_returnsBadRequestWhenServiceThrows() throws Exception {
        when(presetService.createPreset(anyString(), anyList(), any(), any()))
                .thenThrow(new IllegalArgumentException("Preset name is required"));

        mockMvc.perform(post("/hospital/prescription-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"items\":[]}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void createPreset_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(post("/hospital/prescription-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Fever Protocol\",\"items\":[]}")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /** A doctor builds in-clinic presets from inside the consultation (stock medicine + qty). */
    @Test
    @WithMockUser(roles = "DOCTOR")
    void createPreset_savesInClinicPresetForDoctor() throws Exception {
        PrescriptionPreset saved = new PrescriptionPreset();
        saved.setId(2L);
        saved.setName("Dressing Kit");
        saved.setPresetType(PrescriptionPreset.IN_CLINIC);
        saved.setDisplayOrder(0);
        when(presetService.createPreset(eq("Dressing Kit"), anyList(), any(), eq("IN_CLINIC"))).thenReturn(saved);
        when(presetService.getItems(2L)).thenReturn(Collections.emptyList());

        mockMvc.perform(post("/hospital/prescription-presets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dressing Kit\",\"presetType\":\"IN_CLINIC\","
                                + "\"items\":[{\"medicineId\":7,\"medicineName\":\"Betadine\",\"quantity\":2,"
                                + "\"dosage\":\"10ml\",\"frequency\":\"1-0-1\",\"duration\":\"5 Days\"}]}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presetType").value("IN_CLINIC"));
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void updatePreset_returnsOkWhenFound() throws Exception {
        PrescriptionPreset updated = new PrescriptionPreset();
        updated.setId(5L);
        updated.setName("Updated Name");
        when(presetService.updatePreset(eq(5L), eq("Updated Name"), any(), any(), any())).thenReturn(updated);
        when(presetService.getItems(5L)).thenReturn(Collections.emptyList());

        mockMvc.perform(put("/hospital/prescription-presets/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Name\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"));
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void updatePreset_returnsBadRequestWhenNotFound() throws Exception {
        when(presetService.updatePreset(eq(999L), any(), any(), any(), any()))
                .thenThrow(new com.hms.exception.ResourceNotFoundException("Preset not found"));

        mockMvc.perform(put("/hospital/prescription-presets/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\"}")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void deletePreset_returnsOkWhenSuccessful() throws Exception {
        mockMvc.perform(delete("/hospital/prescription-presets/7").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void deletePreset_returnsForbiddenForReceptionist() throws Exception {
        mockMvc.perform(delete("/hospital/prescription-presets/7").with(csrf()))
                .andExpect(status().isForbidden());
    }
}

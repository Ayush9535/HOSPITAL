package com.hms.controller.hospital;

import com.hms.dto.WardResponse;
import com.hms.exception.ResourceNotFoundException;
import com.hms.security.JwtUtil;
import com.hms.service.hospital.WardService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P0-1: ward mutations are HOSPITAL_ADMIN only.
 *
 * <p>The class-level annotation lists four roles because reception and doctors genuinely need the
 * ward and bed *lists* to admit and manage IPD. Before this test, that same list also governed
 * create/update/delete, so a pharmacist could delete a ward. Reads and writes are asserted here
 * together so a future edit cannot widen one by loosening the other.
 */
@WebMvcTest(WardController.class)
@Import(WardControllerRoleMatrixTest.MethodSecurityTestConfig.class)
class WardControllerRoleMatrixTest {

    @MockBean private com.hms.repository.UserRepository r1bUserRepository;
    @MockBean private com.hms.repository.HospitalRepository r1bHospitalRepository;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {}

    @Autowired private MockMvc mockMvc;

    @MockBean private WardService wardService;
    @MockBean private JwtUtil jwtUtil;

    private static final String CREATE_BODY =
            "{\"wardName\":\"General\",\"bedPrice\":100,\"totalBeds\":5,\"floorNumber\":1}";
    private static final String BULK_BODY =
            "{\"wards\":[{\"wardName\":\"General\",\"bedPrice\":100,\"totalBeds\":5,\"floorNumber\":1}]}";
    private static final String UPDATE_BODY = "{\"wardName\":\"Renamed\"}";

    // ---------- writes are denied for every non-admin role ----------

    @Test
    @WithMockUser(roles = "DOCTOR")
    void doctorCannotCreateWard() throws Exception {
        mockMvc.perform(post("/hospital/wards").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden());
        verify(wardService, never()).createWard(any());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void receptionistCannotCreateWard() throws Exception {
        mockMvc.perform(post("/hospital/wards").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden());
        verify(wardService, never()).createWard(any());
    }

    @Test
    @WithMockUser(roles = "PHARMACIST")
    void pharmacistCannotCreateWard() throws Exception {
        mockMvc.perform(post("/hospital/wards").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden());
        verify(wardService, never()).createWard(any());
    }

    @Test
    @WithMockUser(roles = "PHARMACIST")
    void pharmacistCannotBulkCreateWards() throws Exception {
        mockMvc.perform(post("/hospital/wards/bulk").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BULK_BODY))
                .andExpect(status().isForbidden());
        verify(wardService, never()).bulkCreate(any());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void doctorCannotUpdateWard() throws Exception {
        mockMvc.perform(put("/hospital/wards/1").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isForbidden());
        verify(wardService, never()).updateWard(anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void receptionistCannotUpdateWard() throws Exception {
        mockMvc.perform(put("/hospital/wards/1").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isForbidden());
        verify(wardService, never()).updateWard(anyLong(), any());
    }

    /** The headline defect: ward deletion was reachable by a pharmacist. */
    @Test
    @WithMockUser(roles = "PHARMACIST")
    void pharmacistCannotDeleteWard() throws Exception {
        mockMvc.perform(delete("/hospital/wards/1").with(csrf()))
                .andExpect(status().isForbidden());
        verify(wardService, never()).deleteWard(anyLong());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void doctorCannotDeleteWard() throws Exception {
        mockMvc.perform(delete("/hospital/wards/1").with(csrf()))
                .andExpect(status().isForbidden());
        verify(wardService, never()).deleteWard(anyLong());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void receptionistCannotDeleteWard() throws Exception {
        mockMvc.perform(delete("/hospital/wards/1").with(csrf()))
                .andExpect(status().isForbidden());
        verify(wardService, never()).deleteWard(anyLong());
    }

    // ---------- the admin keeps every write ----------

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void adminCanCreateWard() throws Exception {
        when(wardService.createWard(any())).thenReturn(new WardResponse());
        mockMvc.perform(post("/hospital/wards").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void adminCanBulkCreateWards() throws Exception {
        when(wardService.bulkCreate(any())).thenReturn(List.of(new WardResponse()));
        mockMvc.perform(post("/hospital/wards/bulk").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BULK_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void adminCanUpdateWard() throws Exception {
        when(wardService.updateWard(anyLong(), any())).thenReturn(new WardResponse());
        mockMvc.perform(put("/hospital/wards/1").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void adminCanDeleteWard() throws Exception {
        mockMvc.perform(delete("/hospital/wards/1").with(csrf()))
                .andExpect(status().isNoContent());
        verify(wardService).deleteWard(1L);
    }

    // ---------- reads stay open to the roles that need them ----------

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void receptionistCanStillListWards() throws Exception {
        when(wardService.getAllWards()).thenReturn(List.of());
        mockMvc.perform(get("/hospital/wards")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RECEPTIONIST")
    void receptionistCanStillListWardsForAdmission() throws Exception {
        when(wardService.getWardsForAdmission()).thenReturn(List.of());
        mockMvc.perform(get("/hospital/wards/for-admission")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void doctorCanStillListBedsForAWard() throws Exception {
        when(wardService.getBedsForWard(anyLong())).thenReturn(List.of());
        mockMvc.perform(get("/hospital/wards/1/beds")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PHARMACIST")
    void pharmacistCanStillListWards() throws Exception {
        when(wardService.getAllWards()).thenReturn(List.of());
        mockMvc.perform(get("/hospital/wards")).andExpect(status().isOk());
    }

    // ---------- tenant behaviour is unchanged for the role that is allowed through ----------

    /**
     * Another hospital's ward must stay indistinguishable from a missing one. The role gate runs
     * first, so this is only observable for an admin -- exactly the caller who is permitted to try.
     */
    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void foreignTenantWardIsNotFoundRatherThanForbidden() throws Exception {
        doThrow(new ResourceNotFoundException("Ward not found")).when(wardService).deleteWard(99L);
        mockMvc.perform(delete("/hospital/wards/99").with(csrf()))
                .andExpect(status().isNotFound());
    }
}

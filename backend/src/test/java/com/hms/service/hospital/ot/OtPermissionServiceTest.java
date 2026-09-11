package com.hms.service.hospital.ot;

import com.hms.entity.RolePermission;
import com.hms.repository.RolePermissionRepository;
import com.hms.security.OtPermissions;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtPermissionServiceTest {

    private static final Long HOSPITAL = 7L;

    @Mock RolePermissionRepository repository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @InjectMocks OtPermissionService service;

    /** No rows anywhere for a hospital means "never customised": use the defaults. */
    @Test
    void effectiveFor_fallsBackToDefaults_whenHospitalHasNoOverrides() {
        when(repository.countByHospitalId(HOSPITAL)).thenReturn(0L);

        assertThat(service.effectiveFor(HOSPITAL, "RECEPTIONIST"))
                .isEqualTo(OtPermissions.defaultsFor("RECEPTIONIST"))
                .contains(OtPermissions.OT_SCHEDULE);
    }

    /** Once any override exists, the rows are the ONLY truth for every role. */
    @Test
    void effectiveFor_usesRowsOnly_onceTheHospitalHasCustomised() {
        when(repository.countByHospitalId(HOSPITAL)).thenReturn(3L);
        when(repository.findByHospitalIdAndRole(HOSPITAL, "DOCTOR"))
                .thenReturn(List.of(row("DOCTOR", OtPermissions.OT_SCHEDULE)));

        assertThat(service.effectiveFor(HOSPITAL, "DOCTOR"))
                .containsExactly(OtPermissions.OT_SCHEDULE);
    }

    /**
     * The point of the whole layer: a hospital can let its surgeon schedule, with no
     * code change and no new role.
     */
    @Test
    void aHospitalCanGrantSchedulingToDoctors() {
        when(repository.countByHospitalId(HOSPITAL)).thenReturn(1L);
        when(repository.findByHospitalIdAndRole(HOSPITAL, "DOCTOR"))
                .thenReturn(List.of(row("DOCTOR", OtPermissions.OT_CREATE), row("DOCTOR", OtPermissions.OT_SCHEDULE)));

        assertThat(service.effectiveFor(HOSPITAL, "DOCTOR")).contains(OtPermissions.OT_SCHEDULE);
    }

    /** A role revoked down to nothing must stay nothing, not silently reinherit defaults. */
    @Test
    void aRoleRevokedToNothing_staysEmpty() {
        when(repository.countByHospitalId(HOSPITAL)).thenReturn(5L);
        when(repository.findByHospitalIdAndRole(HOSPITAL, "RECEPTIONIST")).thenReturn(List.of());

        assertThat(service.effectiveFor(HOSPITAL, "RECEPTIONIST")).isEmpty();
    }

    @Test
    void updateMatrix_replacesEveryRow_andRejectsUnknownCodes() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        lenient().when(repository.countByHospitalId(HOSPITAL)).thenReturn(2L);
        lenient().when(repository.findByHospitalIdAndRole(any(), any())).thenReturn(List.of());

        service.updateMatrix(Map.of("DOCTOR", List.of(OtPermissions.OT_CREATE, OtPermissions.OT_SCHEDULE)));

        verify(repository).deleteByHospitalId(HOSPITAL);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RolePermission>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void updateMatrix_rejectsAnUnknownPermission() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        assertThatThrownBy(() -> service.updateMatrix(Map.of("DOCTOR", List.of("OT_TAKE_OVER_HOSPITAL"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown permission");
    }

    @Test
    void updateMatrix_rejectsAnUnknownRole() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        assertThatThrownBy(() -> service.updateMatrix(Map.of("JANITOR", List.of(OtPermissions.OT_VIEW))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown role");
    }

    /**
     * Granting nothing to anyone would leave zero rows, which reads back as "use the
     * defaults" -- and would also lock the admin out of the matrix. Keep one marker row.
     */
    @Test
    void updateMatrix_grantingNothing_keepsTheAdminAbleToReachTheMatrix() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        lenient().when(repository.countByHospitalId(HOSPITAL)).thenReturn(1L);
        lenient().when(repository.findByHospitalIdAndRole(any(), any())).thenReturn(List.of());

        service.updateMatrix(Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RolePermission>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        RolePermission marker = captor.getValue().get(0);
        assertThat(marker.getRole()).isEqualTo("HOSPITAL_ADMIN");
        assertThat(marker.getPermissionCode()).isEqualTo(OtPermissions.OT_SETTINGS);
    }

    @Test
    void matrix_coversEveryRole() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        when(repository.countByHospitalId(HOSPITAL)).thenReturn(0L);

        Map<String, Set<String>> matrix = service.matrix();

        assertThat(matrix.keySet()).containsExactlyElementsOf(OtPermissions.ROLES);
    }

    private RolePermission row(String role, String code) {
        RolePermission rp = new RolePermission();
        rp.setHospitalId(HOSPITAL);
        rp.setRole(role);
        rp.setPermissionCode(code);
        return rp;
    }
}

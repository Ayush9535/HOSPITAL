package com.hms.service.hospital.ot;

import com.hms.entity.CaseRole;
import com.hms.entity.Surgery;
import com.hms.entity.SurgeryTeamMember;
import com.hms.repository.CaseRoleRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.repository.SurgeryTeamMemberRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SurgeryTeamServiceTest {

    private static final Long HOSPITAL = 7L;

    @Mock SurgeryTeamMemberRepository teamRepository;
    @Mock CaseRoleRepository caseRoleRepository;
    @Mock SurgeryRepository surgeryRepository;
    @Mock SecurityContextHelper securityHelper;
    @InjectMocks SurgeryTeamService service;

    @BeforeEach
    void setUp() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        Surgery s = new Surgery();
        s.setId(1L);
        s.setHospitalId(HOSPITAL);
        lenient().when(surgeryRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(teamRepository.save(any(SurgeryTeamMember.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void aBuiltInRole_needsNoLookup() {
        SurgeryTeamMember m = service.assign(1L, "PRIMARY_SURGEON", 42L, null);
        assertThat(m.getCaseRoleCode()).isEqualTo("PRIMARY_SURGEON");
        assertThat(m.getUserId()).isEqualTo(42L);
        assertThat(m.getExternalName()).isNull();
    }

    /** Exactly one of a staff member or an external name -- never both, never neither. */
    @Test
    void bothStaffAndExternalName_isRejected() {
        assertThatThrownBy(() -> service.assign(1L, "ANAESTHETIST", 42L, "Dr External"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void neitherStaffNorExternalName_isRejected() {
        assertThatThrownBy(() -> service.assign(1L, "ANAESTHETIST", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void anExternalOperator_isAllowed() {
        SurgeryTeamMember m = service.assign(1L, "PRIMARY_SURGEON", null, "Dr Visiting");
        assertThat(m.getUserId()).isNull();
        assertThat(m.getExternalName()).isEqualTo("Dr Visiting");
    }

    @Test
    void anUnknownRole_isRejected() {
        when(caseRoleRepository.findByHospitalIdAndCode(HOSPITAL, "WIZARD")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assign(1L, "WIZARD", 42L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown role");
    }

    /**
     * The Principle-3 proof: a transplant centre adds HARVEST_SURGEON as DATA, then assigns
     * it -- with no code change anywhere in this service.
     */
    @Test
    void aTransplantCentre_addsHarvestSurgeon_asData_andAssignsIt() {
        when(caseRoleRepository.existsByHospitalIdAndCode(HOSPITAL, "HARVEST_SURGEON")).thenReturn(false);
        when(caseRoleRepository.save(any(CaseRole.class))).thenAnswer(i -> i.getArgument(0));

        CaseRole created = service.addCustomRole("Harvest Surgeon");
        assertThat(created.getCode()).isEqualTo("HARVEST_SURGEON");

        when(caseRoleRepository.findByHospitalIdAndCode(HOSPITAL, "HARVEST_SURGEON"))
                .thenReturn(Optional.of(created));

        SurgeryTeamMember m = service.assign(1L, "HARVEST_SURGEON", 99L, null);
        assertThat(m.getCaseRoleCode()).isEqualTo("HARVEST_SURGEON");
    }

    @Test
    void aCustomRole_thatCollidesWithABuiltIn_isRejected() {
        assertThatThrownBy(() -> service.addCustomRole("Primary Surgeon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("built-in");
    }

    @Test
    void availableRoles_mergesBuiltInsWithCustomOnes() {
        CaseRole perfusionist = new CaseRole();
        perfusionist.setHospitalId(HOSPITAL);
        perfusionist.setCode("PERFUSIONIST");
        perfusionist.setLabel("Perfusionist");
        when(caseRoleRepository.findByHospitalIdAndIsActiveTrueOrderByLabelAsc(HOSPITAL))
                .thenReturn(List.of(perfusionist));

        List<Map<String, Object>> roles = service.availableRoles();

        assertThat(roles).anySatisfy(r -> assertThat(r.get("code")).isEqualTo("PRIMARY_SURGEON"));
        assertThat(roles).anySatisfy(r -> {
            assertThat(r.get("code")).isEqualTo("PERFUSIONIST");
            assertThat(r.get("custom")).isEqualTo(true);
        });
    }
}

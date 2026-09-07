package com.hms.service.hospital;

import com.hms.dto.CreateWardRequest;
import com.hms.dto.UpdateWardRequest;
import com.hms.dto.WardResponse;
import com.hms.entity.Bed;
import com.hms.entity.BedStatus;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.WardRepository;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers WardService.createWard — in particular the bed-count guard added for the CodeQL
 * "user-controlled data in arithmetic expression" finding (total bounded to [0, MAX_BEDS_PER_WARD],
 * bed numbers computed in long) and the sequential bed-code generation.
 */
@ExtendWith(MockitoExtension.class)
class WardServiceTest {

    @Mock WardRepository wardRepository;
    @Mock BedRepository bedRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock HospitalWebSocketHandler webSocketHandler;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock AuditLogService auditLogService;
    @InjectMocks WardService service;

    private CreateWardRequest req(String name, Integer totalBeds) {
        CreateWardRequest r = new CreateWardRequest();
        r.setWardName(name);
        r.setBedPrice(BigDecimal.TEN);
        r.setTotalBeds(totalBeds);
        r.setFloorNumber(1);
        return r;
    }

    private Ward savedWard(Long id, String name, Integer total) {
        Ward w = new Ward();
        w.setWardId(id);
        w.setHospitalId(7L);
        w.setWardName(name);
        w.setTotalBeds(total);
        return w;
    }

    @Test
    void createWard_createsOneBedPerCount_withSequentialCodes() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(wardRepository.save(any())).thenReturn(savedWard(3L, "ICU", 3));
        when(bedRepository.findByWardIdAndHospitalId(3L, 7L)).thenReturn(List.of());
        when(bedRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        WardResponse resp = service.createWard(req("ICU", 3));

        assertThat(resp.getWardName()).isEqualTo("ICU");
        ArgumentCaptor<Bed> captor = ArgumentCaptor.forClass(Bed.class);
        verify(bedRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Bed::getBedCode)
                .containsExactly("ICU-B1", "ICU-B2", "ICU-B3");
        assertThat(captor.getAllValues()).allSatisfy(b -> {
            assertThat(b.getStatus()).isEqualTo("available");
            assertThat(b.getHospitalId()).isEqualTo(7L);
            assertThat(b.getWardId()).isEqualTo(3L);
        });
    }

    @Test
    void createWard_continuesNumbering_afterExistingBeds() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(wardRepository.save(any())).thenReturn(savedWard(3L, "ICU", 2));
        Bed existing = new Bed();
        existing.setBedCode("ICU-B5");
        when(bedRepository.findByWardIdAndHospitalId(3L, 7L)).thenReturn(List.of(existing));
        when(bedRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.createWard(req("ICU", 2));

        ArgumentCaptor<Bed> captor = ArgumentCaptor.forClass(Bed.class);
        verify(bedRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Bed::getBedCode)
                .containsExactly("ICU-B6", "ICU-B7");
    }

    @Test
    void createWard_withNullTotalBeds_createsNoBeds() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(wardRepository.save(any())).thenReturn(savedWard(3L, "ICU", null));
        when(bedRepository.findByWardIdAndHospitalId(3L, 7L)).thenReturn(List.of());

        service.createWard(req("ICU", null));

        verify(bedRepository, never()).save(any());
    }

    @Test
    void getWardsForAdmission_includesAvailableWardWithoutNurseIncharge() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        Ward ward = savedWard(3L, "General", 1);
        ward.setInchargeNurseId(null);
        Bed available = new Bed();
        available.setStatus("available");
        when(wardRepository.findByHospitalId(7L)).thenReturn(List.of(ward));
        when(bedRepository.findByWardIdAndHospitalId(3L, 7L)).thenReturn(List.of(available));

        List<WardResponse> wards = service.getWardsForAdmission();

        assertThat(wards).extracting(WardResponse::getWardId).containsExactly(3L);
    }

    @Test
    void createWard_rejectsCountAboveMax_andCreatesNoBeds() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(wardRepository.save(any())).thenReturn(savedWard(3L, "ICU", 2001));

        assertThatThrownBy(() -> service.createWard(req("ICU", 2001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2000");

        verify(bedRepository, never()).save(any());
    }

    @Test
    void createWard_rejectsNegativeCount() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(wardRepository.save(any())).thenReturn(savedWard(3L, "ICU", -1));

        assertThatThrownBy(() -> service.createWard(req("ICU", -1)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(bedRepository, never()).save(any());
    }

    // ── ICU Phase 2: ward classification (wards.unit_type) ────────────────────

    private Ward typedWard(Long id, String name, String unitType) {
        Ward w = savedWard(id, name, 2);
        w.setUnitType(unitType);
        return w;
    }

    private Bed bedWithStatus(Long id, String status) {
        Bed b = new Bed();
        b.setBedId(id);
        b.setHospitalId(7L);
        b.setWardId(3L);
        b.setBedCode("ICU-B" + id);
        b.setStatus(status);
        return b;
    }

    @Test
    void createWard_withoutAUnitType_defaultsToGeneral() {
        // An existing client that never sends the field keeps creating general wards.
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(wardRepository.save(any())).thenAnswer(i -> {
            Ward w = i.getArgument(0);
            w.setWardId(3L);
            return w;
        });
        when(bedRepository.findByWardIdAndHospitalId(3L, 7L)).thenReturn(List.of());
        when(bedRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        WardResponse resp = service.createWard(req("General-A", 1));

        assertThat(resp.getUnitType()).isEqualTo("GENERAL");
        assertThat(resp.getUnitTypeLabel()).isEqualTo("General Ward");
    }

    @Test
    void createWard_storesTheRequestedUnitType() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(wardRepository.save(any())).thenAnswer(i -> {
            Ward w = i.getArgument(0);
            w.setWardId(3L);
            return w;
        });
        when(bedRepository.findByWardIdAndHospitalId(3L, 7L)).thenReturn(List.of());
        when(bedRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CreateWardRequest r = req("Neonatal", 1);
        r.setUnitType("nicu"); // normalised, not taken verbatim

        WardResponse resp = service.createWard(r);

        assertThat(resp.getUnitType()).isEqualTo("NICU");
        assertThat(resp.getUnitTypeLabel()).isEqualTo("Neonatal ICU");
    }

    @Test
    void createWard_rejectsAnUnknownUnitType() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        CreateWardRequest r = req("Mystery", 1);
        r.setUnitType("SUPER_ICU");

        assertThatThrownBy(() -> service.createWard(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ward unit type");
        verify(wardRepository, never()).save(any());
    }

    @Test
    void updateWard_reclassifiesAWardWithNoOccupiedBed() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserEmail()).thenReturn("admin@h.test");
        Ward existing = typedWard(3L, "Ward-3", "GENERAL");
        when(wardRepository.findById(3L)).thenReturn(java.util.Optional.of(existing));
        when(wardRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(bedRepository.findByWardIdAndHospitalId(3L, 7L))
                .thenReturn(List.of(bedWithStatus(1L, BedStatus.AVAILABLE),
                                    bedWithStatus(2L, BedStatus.CLEANING)));

        UpdateWardRequest r = new UpdateWardRequest();
        r.setUnitType("ICU");

        WardResponse resp = service.updateWard(3L, r);

        assertThat(resp.getUnitType()).isEqualTo("ICU");
        verify(auditLogService).logAction(eq("WARD_UNIT_TYPE_CHANGED"), any(), any(), eq(7L),
                eq("WARD"), eq("3"), any());
    }

    @Test
    void updateWard_refusesToReclassifyAWardWithAnOccupiedBed() {
        // ICU Phase 2 invariant: an ACTIVE critical-care patient and an ICU bed are two views of
        // one fact. Re-typing under the occupants would break that retroactively.
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        Ward existing = typedWard(3L, "Ward-3", "GENERAL");
        when(wardRepository.findById(3L)).thenReturn(java.util.Optional.of(existing));
        when(bedRepository.findByWardIdAndHospitalId(3L, 7L))
                .thenReturn(List.of(bedWithStatus(1L, BedStatus.AVAILABLE),
                                    bedWithStatus(2L, BedStatus.OCCUPIED)));

        UpdateWardRequest r = new UpdateWardRequest();
        r.setUnitType("ICU");

        assertThatThrownBy(() -> service.updateWard(3L, r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occupied beds");
        assertThat(existing.getUnitType()).isEqualTo("GENERAL");
        verify(wardRepository, never()).save(any());
    }

    @Test
    void updateWard_settingTheSameUnitTypeIsANoOp_evenWithOccupiedBeds() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        Ward existing = typedWard(3L, "ICU-1", "ICU");
        when(wardRepository.findById(3L)).thenReturn(java.util.Optional.of(existing));
        when(wardRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateWardRequest r = new UpdateWardRequest();
        r.setUnitType("ICU");

        WardResponse resp = service.updateWard(3L, r);

        assertThat(resp.getUnitType()).isEqualTo("ICU");
        // No bed scan was needed, and nothing was audited as a change.
        verify(bedRepository, never()).findByWardIdAndHospitalId(3L, 7L);
        verify(auditLogService, never()).logAction(eq("WARD_UNIT_TYPE_CHANGED"),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateWard_withoutAUnitType_leavesTheClassificationUntouched() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        Ward existing = typedWard(3L, "ICU-1", "ICU");
        when(wardRepository.findById(3L)).thenReturn(java.util.Optional.of(existing));
        when(wardRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateWardRequest r = new UpdateWardRequest();
        r.setWardName("ICU-One");

        WardResponse resp = service.updateWard(3L, r);

        assertThat(resp.getUnitType()).isEqualTo("ICU");
        assertThat(resp.getWardName()).isEqualTo("ICU-One");
    }

    @Test
    void toResponse_treatsABlankUnitTypeAsGeneral() {
        // A ward migrated by ddl-auto before the column had a DB default was back-filled with ''
        // rather than 'GENERAL'. Blank is not a registry key and must never surface as one.
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        Ward blank = savedWard(3L, "Migrated", 1);
        blank.setUnitType("");
        when(wardRepository.findByHospitalId(7L)).thenReturn(List.of(blank));

        WardResponse resp = service.getAllWards().get(0);

        assertThat(resp.getUnitType()).isEqualTo("GENERAL");
        assertThat(resp.getUnitTypeLabel()).isEqualTo("General Ward");
    }

    @Test
    void toResponse_treatsANullUnitTypeAsGeneral() {
        // A ward row written before the migration has no value; it must never read as ICU.
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        Ward legacy = savedWard(3L, "Legacy", 1);
        legacy.setUnitType(null);
        when(wardRepository.findByHospitalId(7L)).thenReturn(List.of(legacy));

        WardResponse resp = service.getAllWards().get(0);

        assertThat(resp.getUnitType()).isEqualTo("GENERAL");
    }
}

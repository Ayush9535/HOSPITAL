package com.hms.service.hospital.ot;

import com.hms.entity.OtRoom;
import com.hms.entity.Patient;
import com.hms.entity.RecoveryBay;
import com.hms.entity.RecoveryEpisode;
import com.hms.entity.Surgery;
import com.hms.entity.SurgeryStatus;
import com.hms.exception.ConflictException;
import com.hms.repository.PatientRepository;
import com.hms.repository.RecoveryBayRepository;
import com.hms.repository.RecoveryEpisodeRepository;
import com.hms.entity.RecoveryObservation;
import com.hms.repository.RecoveryObservationRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.security.PerformingNurseResolver;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryServiceTest {

    private static final Long HOSPITAL = 7L;
    private static final Long BAY_ID = 30L;
    private static final String BAY_PUBLIC_ID = "bay-public-30";

    @Mock RecoveryEpisodeRepository episodeRepository;
    @Mock RecoveryObservationRepository observationRepository;
    @Mock RecoveryBayRepository bayRepository;
    @Mock SurgeryRepository surgeryRepository;
    @Mock PatientRepository patientRepository;
    @Mock OtPolicyService otPolicyService;
    @Mock SecurityContextHelper securityHelper;
    @Mock PerformingNurseResolver performingNurseResolver;
    @InjectMocks RecoveryService service;

    private final Surgery surgery = new Surgery();
    private final RecoveryBay bay = new RecoveryBay();

    @BeforeEach
    void setUp() {
        lenient().when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        lenient().when(securityHelper.getCurrentUserId()).thenReturn(42L);
        surgery.setId(1L);
        surgery.setHospitalId(HOSPITAL);
        surgery.setPatientId(500L);
        // The theatre was already freed at COMPLETED; recovery does not touch it.
        surgery.setStatus(SurgeryStatus.COMPLETED.name());
        surgery.setOtRoomId(9L);
        lenient().when(surgeryRepository.findById(1L)).thenReturn(Optional.of(surgery));
        lenient().when(episodeRepository.save(any(RecoveryEpisode.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(performingNurseResolver.resolve(any())).thenReturn(null);
        lenient().when(observationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        bay.setId(BAY_ID);
        bay.setPublicId(BAY_PUBLIC_ID);
        bay.setHospitalId(HOSPITAL);
        bay.setName("Bay 1");
        bay.setIsActive(true);
        lenient().when(bayRepository.findByPublicIdAndHospitalId(BAY_PUBLIC_ID, HOSPITAL)).thenReturn(Optional.of(bay));
        lenient().when(bayRepository.findByIdAndHospitalIdForUpdate(BAY_ID, HOSPITAL)).thenReturn(Optional.of(bay));
        lenient().when(episodeRepository.existsActiveByRecoveryBayId(BAY_ID)).thenReturn(false);
    }

    /**
     * The defining Phase 8 regression (ADR C3/D5): a patient can be in recovery while the
     * case is COMPLETED. Recovery is a record; it never re-touches the theatre or the status.
     */
    @Test
    void aPatientInRecovery_doesNotAffectTheCaseStatusOrTheRoom() {
        OtRoom room = new OtRoom();
        room.setId(9L);
        room.setStatus(OtRoom.AVAILABLE);
        when(otPolicyService.resolve(HOSPITAL, OtPolicies.RECOVERY_TRACKING, null)).thenReturn("PACU_EPISODE");
        when(episodeRepository.findBySurgeryId(1L)).thenReturn(Optional.empty());

        RecoveryEpisode e = service.admit(1L, BAY_PUBLIC_ID);

        assertThat(e.getArrivedAt()).isNotNull();
        assertThat(e.getRecoveryBayId()).isEqualTo(BAY_ID);
        // The case is still COMPLETED and the room is untouched by admitting to recovery.
        assertThat(surgery.getStatus()).isEqualTo(SurgeryStatus.COMPLETED.name());
        assertThat(room.getStatus()).isEqualTo(OtRoom.AVAILABLE);
    }

    /** A hospital that tracks no recovery cannot admit to it. */
    @Test
    void admittingWhenRecoveryTrackingIsNone_isRejected() {
        when(otPolicyService.resolve(HOSPITAL, OtPolicies.RECOVERY_TRACKING, null)).thenReturn("NONE");

        assertThatThrownBy(() -> service.admit(1L, BAY_PUBLIC_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not track recovery");
    }

    @Test
    void admittingTwice_isRejected() {
        when(otPolicyService.resolve(HOSPITAL, OtPolicies.RECOVERY_TRACKING, null)).thenReturn("PACU_EPISODE");
        when(episodeRepository.findBySurgeryId(1L)).thenReturn(Optional.of(new RecoveryEpisode()));

        assertThatThrownBy(() -> service.admit(1L, BAY_PUBLIC_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already in recovery");
    }

    /** OT-P0B: a COMPLETED surgery is the only legal predecessor for admission to recovery. */
    @Test
    void admittingASurgeryThatIsNotCompleted_isRejected() {
        surgery.setStatus(SurgeryStatus.IN_PROGRESS.name());
        when(otPolicyService.resolve(HOSPITAL, OtPolicies.RECOVERY_TRACKING, null)).thenReturn("PACU_EPISODE");

        assertThatThrownBy(() -> service.admit(1L, BAY_PUBLIC_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completed");
    }

    /** OT-P0B: no bay selected must fail clearly, not admit to nowhere. */
    @Test
    void admittingWithNoBaySelected_isRejected() {
        when(otPolicyService.resolve(HOSPITAL, OtPolicies.RECOVERY_TRACKING, null)).thenReturn("PACU_EPISODE");
        when(episodeRepository.findBySurgeryId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.admit(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bay must be selected");
    }

    /**
     * OT-P0B core invariant: an occupied bay must fail the transition with a controlled
     * conflict, and the surgery must remain COMPLETED and reachable -- never silently create a
     * second occupant, never leave the patient with no location at all.
     */
    @Test
    void admittingToAnOccupiedBay_isRejectedAsAConflict() {
        when(otPolicyService.resolve(HOSPITAL, OtPolicies.RECOVERY_TRACKING, null)).thenReturn("PACU_EPISODE");
        when(episodeRepository.findBySurgeryId(1L)).thenReturn(Optional.empty());
        when(episodeRepository.existsActiveByRecoveryBayId(BAY_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.admit(1L, BAY_PUBLIC_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("occupied");
        assertThat(surgery.getStatus()).isEqualTo(SurgeryStatus.COMPLETED.name());
    }

    @Test
    void admittingToAnInactiveBay_isRejectedAsAConflict() {
        bay.setIsActive(false);
        when(otPolicyService.resolve(HOSPITAL, OtPolicies.RECOVERY_TRACKING, null)).thenReturn("PACU_EPISODE");
        when(episodeRepository.findBySurgeryId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.admit(1L, BAY_PUBLIC_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not in service");
    }

    /**
     * OT-3 invariant: a completed surgery not yet admitted to recovery must still surface on the
     * board, in the awaiting section -- never nowhere.
     */
    @Test
    void theBoardSurfacesACompletedSurgeryWithNoActiveEpisode() {
        when(episodeRepository.findByHospitalIdAndDischargedAtIsNullOrderByArrivedAtAsc(HOSPITAL))
                .thenReturn(List.of());
        when(surgeryRepository.findByHospitalIdAndStatusOrderByRequestedAtDesc(HOSPITAL, "COMPLETED"))
                .thenReturn(List.of(surgery));
        Patient patient = new Patient();
        patient.setId(500L);
        patient.setName("Jane Doe");
        when(patientRepository.findByIdAndHospitalIdAndIsActiveTrue(500L, HOSPITAL)).thenReturn(Optional.of(patient));

        var board = service.board();

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> awaiting =
                (List<java.util.Map<String, Object>>) board.get("awaitingRecovery");
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> inRecovery =
                (List<java.util.Map<String, Object>>) board.get("inRecovery");

        assertThat(inRecovery).isEmpty();
        assertThat(awaiting).hasSize(1);
        assertThat(awaiting.get(0)).containsEntry("surgeryId", 1L).containsEntry("patientName", "Jane Doe");
    }

    /** A patient already admitted to a bay must appear only in "in recovery", never both lists. */
    @Test
    void theBoardDoesNotDoubleListAPatientAlreadyInRecovery() {
        RecoveryEpisode active = episode();
        active.setRecoveryBayId(BAY_ID);
        when(episodeRepository.findByHospitalIdAndDischargedAtIsNullOrderByArrivedAtAsc(HOSPITAL))
                .thenReturn(List.of(active));
        when(surgeryRepository.findByHospitalIdAndStatusOrderByRequestedAtDesc(HOSPITAL, "COMPLETED"))
                .thenReturn(List.of(surgery));
        when(bayRepository.findByIdAndHospitalId(BAY_ID, HOSPITAL)).thenReturn(Optional.of(bay));
        when(patientRepository.findByIdAndHospitalIdAndIsActiveTrue(500L, HOSPITAL)).thenReturn(Optional.empty());

        var board = service.board();

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> awaiting =
                (List<java.util.Map<String, Object>>) board.get("awaitingRecovery");
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> inRecovery =
                (List<java.util.Map<String, Object>>) board.get("inRecovery");

        assertThat(inRecovery).hasSize(1);
        assertThat(awaiting).isEmpty();
    }

    /**
     * OT-3, found via live MySQL rehearsal: a patient discharged from recovery (to a ward, home,
     * ...) must not reappear in "awaiting recovery" -- that read as "still needs to be admitted",
     * exactly backwards for someone already dispositioned. Only a surgery with NO episode at all
     * belongs in that section; a discharged episode still counts as "already handled".
     */
    @Test
    void theBoardDoesNotShowADischargedPatientAsAwaitingRecoveryAgain() {
        RecoveryEpisode discharged = episode();
        discharged.setRecoveryBayId(BAY_ID);
        discharged.setDischargedAt(java.time.LocalDateTime.now());
        when(episodeRepository.findByHospitalIdAndDischargedAtIsNullOrderByArrivedAtAsc(HOSPITAL))
                .thenReturn(List.of()); // no longer active
        when(episodeRepository.findByHospitalId(HOSPITAL)).thenReturn(List.of(discharged));
        when(surgeryRepository.findByHospitalIdAndStatusOrderByRequestedAtDesc(HOSPITAL, "COMPLETED"))
                .thenReturn(List.of(surgery));

        var board = service.board();

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> awaiting =
                (List<java.util.Map<String, Object>>) board.get("awaitingRecovery");
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> inRecovery =
                (List<java.util.Map<String, Object>>) board.get("inRecovery");

        assertThat(inRecovery).isEmpty();
        assertThat(awaiting).isEmpty();
    }

    @Test
    void anAldreteScoreOutOfRange_isRejected() {
        when(episodeRepository.findBySurgeryId(1L)).thenReturn(Optional.of(episode()));
        assertThatThrownBy(() -> service.observe(1L, 12, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 10");
    }

    @Test
    void observingBeforeAdmission_isRejected() {
        when(episodeRepository.findBySurgeryId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.observe(1L, 8, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Admit the patient to recovery first");
    }

    @Test
    void dischargeToAnUnknownDestination_isRejected() {
        when(episodeRepository.findBySurgeryId(1L)).thenReturn(Optional.of(episode()));
        assertThatThrownBy(() -> service.discharge(1L, "MARS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid destination");
    }

    @Test
    void dischargeToTheWard_recordsTheDestinationAndTime() {
        when(episodeRepository.findBySurgeryId(1L)).thenReturn(Optional.of(episode()));
        RecoveryEpisode e = service.discharge(1L, "WARD");
        assertThat(e.getTransferDestination()).isEqualTo("WARD");
        assertThat(e.getDischargedAt()).isNotNull();
    }

    private RecoveryEpisode episode() {
        RecoveryEpisode e = new RecoveryEpisode();
        e.setId(3L);
        e.setHospitalId(HOSPITAL);
        e.setSurgeryId(1L);
        e.setPatientId(500L);
        return e;
    }
}

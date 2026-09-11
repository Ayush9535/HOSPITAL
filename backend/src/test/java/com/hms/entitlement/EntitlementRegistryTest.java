package com.hms.entitlement;

import com.hms.entity.HospitalType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EntitlementRegistryTest {

    @Test
    void normalizesValidPlanModules() {
        assertThat(EntitlementRegistry.normalizePlanModules(HospitalType.CLINIC, List.of(" opd ", "billing")))
                .containsExactly("OPD", "BILLING");
    }

    @Test
    void normalizesAppointmentsWithItsRequiredOpdCapability() {
        assertThat(EntitlementRegistry.normalizePlanModules(HospitalType.HOSPITAL,
                List.of(EntitlementRegistry.APPOINTMENTS)))
                .containsExactly(EntitlementRegistry.APPOINTMENTS, EntitlementRegistry.OPD);
    }

    @Test
    void rejectsUnknownBlankDuplicateInternalAndUnavailableModules() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                EntitlementRegistry.normalizePlanModules(HospitalType.HOSPITAL, List.of("NOT_A_REAL_MODULE")));
        assertThatIllegalArgumentException().isThrownBy(() ->
                EntitlementRegistry.normalizePlanModules(HospitalType.HOSPITAL, List.of(" ")));
        assertThatIllegalArgumentException().isThrownBy(() ->
                EntitlementRegistry.normalizePlanModules(HospitalType.HOSPITAL, List.of("OPD", "opd")));
        assertThatIllegalArgumentException().isThrownBy(() ->
                EntitlementRegistry.normalizePlanModules(HospitalType.HOSPITAL, List.of(EntitlementRegistry.PHARMACY_BRANCH)));
    }

    @Test
    void rejectsFacilityMismatchAndAllowsPharmacyTier() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                EntitlementRegistry.normalizePlanModules(HospitalType.HOSPITAL,
                        List.of(EntitlementRegistry.TIER_SINGLE_PHARMACY)));

        assertThat(EntitlementRegistry.normalizePlanModules(HospitalType.PHARMACY,
                List.of(EntitlementRegistry.TIER_MULTI_PHARMACY)))
                .containsExactly(EntitlementRegistry.TIER_MULTI_PHARMACY);
    }

    @Test
    void onlyMultiPharmacyResolvesBranchCapability() {
        assertThat(EntitlementRegistry.resolve(List.of(EntitlementRegistry.TIER_MULTI_PHARMACY)))
                .contains(EntitlementRegistry.PHARMACY_BRANCH);
        assertThat(EntitlementRegistry.resolve(List.of(EntitlementRegistry.TIER_SINGLE_PHARMACY)))
                .doesNotContain(EntitlementRegistry.PHARMACY_BRANCH);
        assertThat(EntitlementRegistry.resolve(List.of(EntitlementRegistry.TIER_SINGLE_PHARMACIST_ADMIN)))
                .doesNotContain(EntitlementRegistry.PHARMACY_BRANCH);
    }

    @Test
    void catalogContainsOnlySelectableCapabilities() {
        assertThat(EntitlementRegistry.catalogFor(HospitalType.HOSPITAL))
                .extracting(EntitlementRegistry.Capability::key)
                .contains("OPD", "IPD")
                .doesNotContain("PATHOLOGY", "CORE", "PHARMACY_BRANCH");
        assertThat(EntitlementRegistry.catalogFor(HospitalType.PHARMACY))
                .extracting(EntitlementRegistry.Capability::key)
                .containsExactly(EntitlementRegistry.TIER_SINGLE_PHARMACIST_ADMIN,
                        EntitlementRegistry.TIER_SINGLE_PHARMACY, EntitlementRegistry.TIER_MULTI_PHARMACY);
    }

    @Test
    void appointmentsImplyOpd() {
        assertThat(EntitlementRegistry.resolve(List.of(EntitlementRegistry.APPOINTMENTS)))
                .contains(EntitlementRegistry.APPOINTMENTS, EntitlementRegistry.OPD);
    }

    @Test
    void opdDoesNotImplyAppointments() {
        assertThat(EntitlementRegistry.resolve(List.of(EntitlementRegistry.OPD)))
                .contains(EntitlementRegistry.OPD)
                .doesNotContain(EntitlementRegistry.APPOINTMENTS);
    }

    @Test
    void walkInOpdRemainsValidWithoutAppointments() {
        assertThat(EntitlementRegistry.resolve(List.of(EntitlementRegistry.OPD, EntitlementRegistry.BILLING)))
                .contains(EntitlementRegistry.OPD, EntitlementRegistry.BILLING)
                .doesNotContain(EntitlementRegistry.APPOINTMENTS);
    }
}

package com.hms.service.hospital.icu;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ICU Phase 2 — the registry is the single source of "is this ward an ICU?", so its edges
 * matter more than its happy path. An unknown or missing type must read as NOT critical care:
 * a ward that silently appears on the ICU board is worse than one that is missing from it.
 */
class CareUnitRegistryTest {

    @Test
    void containsTheEightDeclaredTypes() {
        assertThat(CareUnitRegistry.UNIT_TYPES).hasSize(8);
        assertThat(CareUnitRegistry.UNIT_TYPES.stream().map(CareUnitRegistry.UnitType::key))
                .containsExactly("GENERAL", "ICU", "MICU", "SICU", "NICU", "PICU", "CCU", "HDU");
    }

    @Test
    void generalIsNotCriticalCare_everyOtherTypeIs() {
        assertThat(CareUnitRegistry.isCriticalCare("GENERAL")).isFalse();
        for (String key : new String[] { "ICU", "MICU", "SICU", "NICU", "PICU", "CCU", "HDU" }) {
            assertThat(CareUnitRegistry.isCriticalCare(key)).as(key).isTrue();
        }
    }

    @Test
    void criticalCareKeys_excludeGeneral() {
        assertThat(CareUnitRegistry.criticalCareKeys())
                .containsExactly("ICU", "MICU", "SICU", "NICU", "PICU", "CCU", "HDU")
                .doesNotContain("GENERAL");
    }

    @Test
    void unknownOrNullType_isNotCriticalCare() {
        // A ward whose column predates the migration, or carries a key this build does not
        // know, must never appear on the ICU board.
        assertThat(CareUnitRegistry.isCriticalCare(null)).isFalse();
        assertThat(CareUnitRegistry.isCriticalCare("")).isFalse();
        assertThat(CareUnitRegistry.isCriticalCare("SOMETHING_ELSE")).isFalse();
        assertThat(CareUnitRegistry.isCriticalCare("icu")).isFalse(); // keys are stored upper-case
    }

    @Test
    void isValidKey_rejectsUnknownAndNull() {
        assertThat(CareUnitRegistry.isValidKey("NICU")).isTrue();
        assertThat(CareUnitRegistry.isValidKey("WARD")).isFalse();
        assertThat(CareUnitRegistry.isValidKey(null)).isFalse();
    }

    @Test
    void labelOf_knownKey_andFallback() {
        assertThat(CareUnitRegistry.labelOf("NICU")).isEqualTo("Neonatal ICU");
        assertThat(CareUnitRegistry.labelOf("GENERAL")).isEqualTo("General Ward");
        assertThat(CareUnitRegistry.labelOf("MYSTERY")).isEqualTo("MYSTERY");
        assertThat(CareUnitRegistry.labelOf(null)).isEqualTo("GENERAL");
    }

    @Test
    void normalize_defaultsToGeneral_andUppercases() {
        assertThat(CareUnitRegistry.normalize(null)).isEqualTo("GENERAL");
        assertThat(CareUnitRegistry.normalize("   ")).isEqualTo("GENERAL");
        assertThat(CareUnitRegistry.normalize("icu")).isEqualTo("ICU");
        assertThat(CareUnitRegistry.normalize(" Hdu ")).isEqualTo("HDU");
    }

    @Test
    void normalize_rejectsUnknownKey() {
        assertThatThrownBy(() -> CareUnitRegistry.normalize("SUPER_ICU"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ward unit type");
    }
}

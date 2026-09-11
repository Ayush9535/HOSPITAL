package com.hms.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks OtPermissions.DEFAULTS against silent drift.
 *
 * Originally Phase 2's exit criterion (day-1 access must survive the hasRole(...) ->
 * hasAuthority('OT_*') refactor unchanged) -- superseded by OT-P0A. That release found three
 * codes (OT_ASSIGN_TEAM, OT_RECOVERY, OT_TRANSFER) granted to nobody, and HOSPITAL_ADMIN holding
 * clinical execution powers (schedule/start/complete/...) no UI ever exercised. EXPECTED_ACCESS
 * below is the v2 baseline those fixes established, reviewed against the actual workflow rather
 * than transcribed from old @PreAuthorize checks. A future change to DEFAULTS is expected to
 * require updating this table -- that diff is exactly the review this test forces.
 */
class OtPermissionsParityTest {

    /**
     * Matches both forms an OT endpoint may use: a single required permission, or a set of
     * alternatives. hasAnyAuthority is legitimate -- recording a milestone is open to anyone with
     * a hand in the case -- so the parity check reads the union rather than rejecting it.
     */
    private static final Pattern AUTHORITY = Pattern.compile("'([A-Z_]+)'");

    /** Roles that should reach each OT endpoint, under the v2 (OT-P0A) defaults. */
    private static final Map<String, Set<String>> EXPECTED_ACCESS = new LinkedHashMap<>();
    static {
        // SurgeryController -- DOCTOR requests/reads its own board; OT_INCHARGE, as the
        // theatre-owning role, gets the full clinical set. RECEPTIONIST keeps front-desk
        // scheduling/execution; HOSPITAL_ADMIN deliberately does not appear on any of these.
        EXPECTED_ACCESS.put("SurgeryController#create", Set.of("DOCTOR", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("SurgeryController#activeForAdmission",
                Set.of("DOCTOR", "RECEPTIONIST", "NURSE", "NURSE_INCHARGE", "OT_INCHARGE", "HOSPITAL_ADMIN"));
        EXPECTED_ACCESS.put("SurgeryController#requests", Set.of("RECEPTIONIST", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("SurgeryController#board", Set.of("RECEPTIONIST", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("SurgeryController#myBoard", Set.of("DOCTOR", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("SurgeryController#surgeons", Set.of("RECEPTIONIST", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("SurgeryController#schedule", Set.of("RECEPTIONIST", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("SurgeryController#start", Set.of("RECEPTIONIST", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("SurgeryController#complete", Set.of("RECEPTIONIST", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("SurgeryController#cancel", Set.of("RECEPTIONIST", "OT_INCHARGE"));
        // SurgeryFormController -- NURSE fills forms bedside; OT_INCHARGE can too; NURSE_INCHARGE
        // reads (ward-side oversight) but does not sign; HOSPITAL_ADMIN reads only.
        EXPECTED_ACCESS.put("SurgeryFormController#save", Set.of("NURSE", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("SurgeryFormController#get",
                Set.of("NURSE", "DOCTOR", "NURSE_INCHARGE", "OT_INCHARGE", "HOSPITAL_ADMIN"));
        EXPECTED_ACCESS.put("SurgeryFormController#listSaved",
                Set.of("NURSE", "DOCTOR", "NURSE_INCHARGE", "OT_INCHARGE", "HOSPITAL_ADMIN"));
        // SurgeryExecutionController -- the theatre floor. This controller was outside the parity
        // baseline, which is how a real mismatch survived: the WHO checklist is signed under
        // OT_TIME_OUT, which reception does not hold, while the only screen that offers the
        // signature is mounted on reception's dashboard. Signing SIGN_IN returned Access Denied on
        // staging. The permissions below are the clinically correct ones and are now pinned, so a
        // future edit to either the endpoint or the defaults has to be deliberate.
        EXPECTED_ACCESS.put("SurgeryExecutionController#milestones",
                Set.of("DOCTOR", "RECEPTIONIST", "NURSE", "NURSE_INCHARGE", "OT_INCHARGE", "HOSPITAL_ADMIN"));
        EXPECTED_ACCESS.put("SurgeryExecutionController#checklist",
                Set.of("DOCTOR", "RECEPTIONIST", "NURSE", "NURSE_INCHARGE", "OT_INCHARGE", "HOSPITAL_ADMIN"));
        EXPECTED_ACCESS.put("SurgeryExecutionController#recordMilestone",
                Set.of("RECEPTIONIST", "NURSE", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("SurgeryExecutionController#signPhase", Set.of("NURSE", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("SurgeryExecutionController#operativeNote",
                Set.of("RECEPTIONIST", "OT_INCHARGE"));
        // SurgeryTeamController / RecoveryController -- assignment and PACU.
        EXPECTED_ACCESS.put("SurgeryTeamController#team",
                Set.of("DOCTOR", "RECEPTIONIST", "NURSE", "NURSE_INCHARGE", "OT_INCHARGE", "HOSPITAL_ADMIN"));
        // NURSE_INCHARGE is absent here on purpose: it does not hold OT_ASSIGN_TEAM in DEFAULTS.
        // The OtPermissions javadoc claimed otherwise and has been corrected to match the code --
        // the fix is to the sentence, not to the grant.
        EXPECTED_ACCESS.put("SurgeryTeamController#assign", Set.of("DOCTOR", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("SurgeryTeamController#remove", Set.of("DOCTOR", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("RecoveryController#admit", Set.of("NURSE", "NURSE_INCHARGE", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("RecoveryController#observe", Set.of("NURSE", "NURSE_INCHARGE", "OT_INCHARGE"));
        EXPECTED_ACCESS.put("RecoveryController#discharge",
                Set.of("RECEPTIONIST", "NURSE_INCHARGE", "OT_INCHARGE"));
    }

    /** Endpoint -> the permission(s) its @PreAuthorize now demands. */
    private Map<String, Set<String>> currentPermissions() throws Exception {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String simpleName : Set.of("SurgeryController", "SurgeryFormController",
                "SurgeryExecutionController", "SurgeryTeamController", "RecoveryController")) {
            Class<?> type = Class.forName("com.hms.controller.hospital." + simpleName);
            for (Method m : type.getDeclaredMethods()) {
                PreAuthorize pa = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
                if (pa == null) continue;
                assertThat(pa.value())
                        .as("%s#%s still authorizes on a role, not a permission", simpleName, m.getName())
                        .doesNotContain("hasRole").doesNotContain("hasAnyRole");
                Matcher matcher = AUTHORITY.matcher(pa.value());
                Set<String> required = new TreeSet<>();
                while (matcher.find()) required.add(matcher.group(1));
                assertThat(required)
                        .as("%s#%s declares no OT permission: %s", simpleName, m.getName(), pa.value())
                        .isNotEmpty();
                out.put(simpleName + "#" + m.getName(), required);
            }
        }
        return out;
    }

    /** Roles that hold ANY of the required permissions -- the union hasAnyAuthority expresses. */
    private Set<String> rolesHolding(Set<String> permissions) {
        Set<String> roles = new TreeSet<>();
        for (String role : OtPermissions.ROLES) {
            Set<String> held = OtPermissions.defaultsFor(role);
            if (permissions.stream().anyMatch(held::contains)) roles.add(role);
        }
        return roles;
    }

    @Test
    void everyOtEndpoint_authorizesOnAPermission_notARole() throws Exception {
        assertThat(currentPermissions()).isNotEmpty();
    }

    @Test
    void currentAccess_matchesTheReviewedV2Baseline() throws Exception {
        Map<String, Set<String>> permissions = currentPermissions();
        Map<String, Set<String>> mismatches = new LinkedHashMap<>();

        for (Map.Entry<String, Set<String>> expected : EXPECTED_ACCESS.entrySet()) {
            String endpoint = expected.getKey();
            Set<String> permission = permissions.get(endpoint);
            assertThat(permission).as("no @PreAuthorize found for %s", endpoint).isNotNull();

            Set<String> now = rolesHolding(permission);
            Set<String> before = new TreeSet<>(expected.getValue());
            if (!now.equals(before)) {
                mismatches.put(endpoint + " (" + permission + ")", diff(before, now));
            }
        }
        assertThat(mismatches)
                .as("Access changed for these endpoints (+added/-removed vs the reviewed v2 "
                        + "baseline). If intentional, update EXPECTED_ACCESS with the same review "
                        + "this table represents; if not, DEFAULTS regressed.")
                .isEmpty();
    }

    private Set<String> diff(Set<String> before, Set<String> now) {
        Set<String> out = new LinkedHashSet<>();
        for (String r : now) if (!before.contains(r)) out.add("+" + r);
        for (String r : before) if (!now.contains(r)) out.add("-" + r);
        return out;
    }

    /** Nobody should be able to grant a code that no endpoint recognises. */
    @Test
    void everyDefaultedPermission_isAValidCode() {
        for (String role : OtPermissions.ROLES) {
            for (String code : OtPermissions.defaultsFor(role)) {
                assertThat(OtPermissions.isValid(code))
                        .as("%s defaults to unknown permission %s", role, code).isTrue();
            }
        }
    }

    /**
     * OT-P0A: the whole point of the fix. Every code in the vocabulary must have at least one
     * intended owner, or the action behind it is unreachable in every hospital that has not
     * customised its matrix -- exactly how OT_ASSIGN_TEAM/OT_RECOVERY/OT_TRANSFER went dark.
     */
    @Test
    void everyPermissionCode_isGrantedToAtLeastOneRoleByDefault() {
        Set<String> granted = new TreeSet<>();
        for (String role : OtPermissions.ROLES) granted.addAll(OtPermissions.defaultsFor(role));

        Set<String> orphaned = new TreeSet<>(OtPermissions.ALL);
        orphaned.removeAll(granted);

        assertThat(orphaned).as("permission codes with no default owner").isEmpty();
    }

    /** A hospital admin must always retain the ability to reach the matrix itself. */
    @Test
    void hospitalAdmin_holdsOtSettingsByDefault() {
        assertThat(OtPermissions.defaultsFor("HOSPITAL_ADMIN")).contains(OtPermissions.OT_SETTINGS);
    }

    /**
     * OT-P0A: Hospital Admin is configuration and view only by default -- routine clinical
     * execution is not granted accidentally through a broad default. A hospital that wants its
     * admin to act clinically opts in explicitly via the permission matrix, same as any other
     * customisation.
     */
    @Test
    void hospitalAdmin_doesNotHoldRoutineClinicalExecutionByDefault() {
        Set<String> clinicalExecution = Set.of(
                OtPermissions.OT_CREATE, OtPermissions.OT_APPROVE, OtPermissions.OT_SCHEDULE,
                OtPermissions.OT_RESCHEDULE, OtPermissions.OT_CANCEL, OtPermissions.OT_ASSIGN_ROOM,
                OtPermissions.OT_ASSIGN_TEAM, OtPermissions.OT_PRE_OP, OtPermissions.OT_ANAESTHESIA_CLEARANCE,
                OtPermissions.OT_EMERGENCY_OVERRIDE, OtPermissions.OT_TIME_OUT, OtPermissions.OT_START,
                OtPermissions.OT_COMPLETE, OtPermissions.OT_RECOVERY, OtPermissions.OT_TRANSFER,
                OtPermissions.OT_CLOSE, OtPermissions.OT_FORM_EDIT);

        Set<String> adminDefaults = OtPermissions.defaultsFor("HOSPITAL_ADMIN");
        Set<String> accidental = new TreeSet<>(adminDefaults);
        accidental.retainAll(clinicalExecution);

        assertThat(accidental).as("clinical execution codes HOSPITAL_ADMIN holds by default").isEmpty();
    }

    /**
     * OT-P0A: OT_INCHARGE is the theatre-owning role and must not default to nothing, as it did
     * before this fix (every endpoint 403'd for it on day one).
     */
    @Test
    void otIncharge_noLongerDefaultsToNoOtAccess() {
        assertThat(OtPermissions.defaultsFor("OT_INCHARGE")).isNotEmpty();
    }
}

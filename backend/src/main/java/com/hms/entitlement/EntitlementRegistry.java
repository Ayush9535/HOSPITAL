package com.hms.entitlement;

import com.hms.entity.HospitalType;

import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * EntitlementRegistry — the single declaration of what each kind of tenant may have.
 *
 * <p>Before this, the answer was spread across four places that did not know about each other:
 * {@code PlansTab.MODULES_BY_TYPE} in the frontend decided what could be sold, {@code @RequireModule}
 * decided what was gated, the {@code /clinic} and {@code /pharmacy} URL aliases decided what was
 * reachable, and {@code @TenantType} decided what a facility type could see. Each was maintained by
 * hand, per controller, and they drifted: OPD is reachable by a pharmacy while the OPD vitals
 * settings behind it are not; a clinic can admit an inpatient but cannot open the admission form.
 *
 * <p><b>This class only declares. It enforces nothing.</b> Wiring the aspects to consult it is a
 * later checkpoint, deliberately, because doing both at once would change what live tenants can
 * reach in the same change that first writes the model down. The one thing it does affect today is
 * plan creation: a platform admin can no longer build a plan whose modules are meaningless for its
 * type (see {@link #validatePlanModules}). Nothing about an existing tenant's access changes.
 *
 * <p>Kept as a Java registry rather than a table, matching {@code OtPermissions},
 * {@code FormRegistry} and {@code VitalRegistry}: these keys are compile-time constants that
 * endpoints and plans reference by name.
 */
public final class EntitlementRegistry {

    private EntitlementRegistry() {
    }

    // ── module keys ───────────────────────────────────────────────────────────

    /** Patients, staff, auth, audit, tickets, presets. Every tenant has these; no plan grants them. */
    public static final String CORE = "CORE";

    public static final String OPD = "OPD";
    public static final String IPD = "IPD";
    public static final String APPOINTMENTS = "APPOINTMENTS";
    public static final String BILLING = "BILLING";
    public static final String PHARMACY = "PHARMACY";
    public static final String MEDICAL_INVENTORY = "MEDICAL_INVENTORY";
    public static final String HOSPITAL_INVENTORY = "HOSPITAL_INVENTORY";
    public static final String REPORTS = "REPORTS";
    public static final String NURSING = "NURSING";
    public static final String OT = "OT";

    /**
     * Critical care — the ICU dashboard and bed board.
     *
     * <p>Depends on {@link #IPD} rather than being implied by it: an ICU bed is an inpatient
     * bed, so ICU without IPD is meaningless, but a hospital can run IPD with no critical-care
     * unit at all. It is therefore sold separately and listed on the plan in its own right.
     */
    public static final String ICU = "ICU";

    /** Wards and beds exist to support admission and allocation, so IPD carries them. */
    public static final String WARDS = "WARDS";
    public static final String BEDS = "BEDS";

    /**
     * Inpatient vitals, nursing notes, initial and vulnerability assessments, sugar charts,
     * medication administration and admission forms.
     *
     * <p>Granted by IPD, not by NURSING. Every one of these records is keyed to an
     * {@code ipdAdmissionId} and the services reject a request without one, so a tenant with no
     * admissions has nothing to attach a record to. NURSING governs <em>who</em> may write them —
     * ward scope, performing-nurse resolution, the separate-nurse-login setting — not whether they
     * can exist.
     *
     * <p>Distinct from {@link #OPD}'s vitals configuration, which decides which vitals an OPD visit
     * captures and is hospital-wide rather than admission-scoped. Two different capabilities that
     * share a word.
     */
    public static final String CLINICAL_RECORDS = "CLINICAL_RECORDS";

    /** Multi-outlet branch management. Granted by a pharmacy tier, not sold as a module. */
    public static final String PHARMACY_BRANCH = "PHARMACY_BRANCH";

    // ── pharmacy tiers ────────────────────────────────────────────────────────
    //
    // Tiers currently live in the same list as modules, because applyPlanToHospital copies
    // plan.modules verbatim onto the hospital. Separating them is a later checkpoint; until then
    // the registry has to accept them where they actually appear, or every existing pharmacy plan
    // would become uneditable.

    public static final String TIER_SINGLE_PHARMACIST_ADMIN = "SINGLE_PHARMACIST_ADMIN";
    public static final String TIER_SINGLE_PHARMACY = "SINGLE_PHARMACY";
    public static final String TIER_MULTI_PHARMACY = "MULTI_PHARMACY";

    public static final Set<String> PHARMACY_TIERS = Set.of(
            TIER_SINGLE_PHARMACIST_ADMIN, TIER_SINGLE_PHARMACY, TIER_MULTI_PHARMACY);

    /**
     * A plan may also carry IN_CLINIC, which is not a module but an operational toggle
     * ({@code applyPlanToHospital} adds and removes it from the hospital's list based on
     * {@code Plan.inClinic}). Accepted so a plan carrying it stays editable.
     */
    public static final String IN_CLINIC = "IN_CLINIC";

    // ── what each facility type may be SOLD ───────────────────────────────────
    //
    // Mirrors PlansTab.MODULES_BY_TYPE, which until now was the only place this was written down
    // and lived in the frontend.

    private static final Map<HospitalType, Set<String>> SELLABLE = Map.of(
            HospitalType.HOSPITAL, Set.of(OPD, IPD, PHARMACY, BILLING, APPOINTMENTS,
                    MEDICAL_INVENTORY, HOSPITAL_INVENTORY, REPORTS, OT, NURSING, ICU),
            HospitalType.CLINIC, Set.of(OPD, PHARMACY, BILLING, APPOINTMENTS,
                    MEDICAL_INVENTORY, REPORTS),
            // PHARMACY plans carry a tier plus the PHARMACY base module, which
            // ensurePharmacyBaseModule already adds on every write.
            HospitalType.PHARMACY, Set.of(PHARMACY, TIER_SINGLE_PHARMACIST_ADMIN,
                    TIER_SINGLE_PHARMACY, TIER_MULTI_PHARMACY));

    /**
     * Modules a plan never lists because something else grants them. Selling them directly would be
     * meaningless — IPD already implies its wards.
     */
    private static final Map<String, Set<String>> IMPLIED_BY = new LinkedHashMap<>();
    static {
        // Booking requires the OPD case and consultation workflow, but OPD remains useful for
        // walk-in hospitals that do not sell appointments.
        IMPLIED_BY.put(APPOINTMENTS, Set.of(OPD));
        IMPLIED_BY.put(IPD, Set.of(WARDS, BEDS, CLINICAL_RECORDS));
        IMPLIED_BY.put(TIER_MULTI_PHARMACY, Set.of(PHARMACY_BRANCH));
    }

    /** Every key the registry knows, sellable or implied. */
    public static final Set<String> ALL_MODULES = Set.of(
            CORE, OPD, IPD, WARDS, BEDS, CLINICAL_RECORDS, APPOINTMENTS, BILLING, PHARMACY,
            PHARMACY_BRANCH, MEDICAL_INVENTORY, HOSPITAL_INVENTORY, REPORTS, NURSING, OT, ICU,
            TIER_SINGLE_PHARMACIST_ADMIN, TIER_SINGLE_PHARMACY, TIER_MULTI_PHARMACY, IN_CLINIC);

    private static final Set<String> INTERNAL_KEYS = Set.of(
            CORE, IN_CLINIC, WARDS, BEDS, CLINICAL_RECORDS, PHARMACY_BRANCH);

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry(OPD, "OPD"),
            Map.entry(IPD, "IPD"),
            Map.entry(PHARMACY, "Pharmacy"),
            Map.entry(BILLING, "Billing"),
            Map.entry(APPOINTMENTS, "Appointments"),
            Map.entry(MEDICAL_INVENTORY, "Medical Inventory"),
            Map.entry(HOSPITAL_INVENTORY, "Hospital Inventory"),
            Map.entry(REPORTS, "Reports & Analytics"),
            Map.entry(NURSING, "Nursing"),
            Map.entry(OT, "Operation Theatre"),
            Map.entry(TIER_SINGLE_PHARMACIST_ADMIN, "Single Pharmacist Admin"),
            Map.entry(TIER_SINGLE_PHARMACY, "Single Pharmacy"),
            Map.entry(TIER_MULTI_PHARMACY, "Multi Pharmacy"));

    private static final List<String> CATALOG_ORDER = List.of(
            OPD, IPD, PHARMACY, BILLING, APPOINTMENTS, MEDICAL_INVENTORY, HOSPITAL_INVENTORY,
            REPORTS, OT, NURSING, TIER_SINGLE_PHARMACIST_ADMIN, TIER_SINGLE_PHARMACY,
            TIER_MULTI_PHARMACY);

    // ── queries ───────────────────────────────────────────────────────────────

    /** May a plan of this facility type list this module? */
    public static boolean isSellable(HospitalType type, String module) {
        return type != null && SELLABLE.getOrDefault(type, Set.of()).contains(module);
    }

    public static Set<String> sellableFor(HospitalType type) {
        return new LinkedHashSet<>(SELLABLE.getOrDefault(type, Set.of()));
    }

    /** Entries a Super Admin may select when creating or editing a plan. */
    public static List<Capability> catalogFor(HospitalType type) {
        List<Capability> catalog = new ArrayList<>();
        for (String key : CATALOG_ORDER) {
            if (!isSellable(type, key)) {
                continue;
            }
            // PHARMACY is stored automatically for pharmacy plans; operators choose a tier instead.
            if (type == HospitalType.PHARMACY && PHARMACY.equals(key)) {
                continue;
            }
            catalog.add(new Capability(key, LABELS.getOrDefault(key, key), PHARMACY_TIERS.contains(key)));
        }
        return catalog;
    }

    public record Capability(String key, String label, boolean pharmacyTier) {
    }

    /**
     * Expand what a tenant actually holds: everything granted, everything those grants imply, and
     * CORE, which every tenant has.
     *
     * <p>Not consulted by any gate yet — this is the function the aspects will call once
     * enforcement is switched on.
     */
    public static Set<String> resolve(Collection<String> granted) {
        Set<String> out = new LinkedHashSet<>();
        out.add(CORE);
        if (granted != null) {
            for (String g : granted) {
                if (g == null) continue;
                out.add(g);
                out.addAll(IMPLIED_BY.getOrDefault(g, Set.of()));
            }
        }
        return out;
    }

    /**
     * Normalize and validate modules supplied by a Super Admin. Internal and implied capabilities
     * are never persisted from operator input.
     */
    public static List<String> normalizePlanModules(HospitalType type, Collection<String> modules) {
        if (type == null) {
            throw new IllegalArgumentException("Plan type is required");
        }
        if (modules == null) {
            return new ArrayList<>();
        }

        List<String> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : modules) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("Plan module keys cannot be blank");
            }
            String key = raw.trim().toUpperCase(Locale.ROOT);
            if (!ALL_MODULES.contains(key)) {
                throw new IllegalArgumentException("Unknown plan module: " + key);
            }
            if (INTERNAL_KEYS.contains(key)) {
                throw new IllegalArgumentException("Plan module cannot be selected directly: " + key);
            }
            if (!isSellable(type, key)) {
                throw new IllegalArgumentException("Module " + key + " cannot be sold to a " + type + " plan");
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Duplicate plan module: " + key);
            }
            normalized.add(key);
        }

        long pharmacyTierCount = normalized.stream().filter(PHARMACY_TIERS::contains).count();
        if (pharmacyTierCount > 1) {
            throw new IllegalArgumentException("A pharmacy plan may select only one pharmacy tier");
        }

        // Persist only dependencies that are also sellable for this plan type. APPOINTMENTS
        // therefore carries OPD, while internal IPD grants remain runtime-derived.
        for (String implied : resolve(normalized)) {
            if (isSellable(type, implied) && !normalized.contains(implied)) {
                normalized.add(implied);
            }
        }
        return normalized;
    }

    /**
     * Normalize modules copied from a persisted plan without rejecting legacy combinations.
     * Operator input is validated by {@link #normalizePlanModules}; application still adds the
     * sellable dependencies required for a tenant to operate safely.
     */
    public static List<String> normalizeAppliedPlanModules(HospitalType type, Collection<String> modules) {
        Set<String> normalized = new LinkedHashSet<>();
        if (modules != null) {
            for (String raw : modules) {
                if (raw != null && !raw.isBlank()) {
                    normalized.add(raw.trim().toUpperCase(Locale.ROOT));
                }
            }
        }

        for (String implied : resolve(normalized)) {
            if (isSellable(type, implied)) {
                normalized.add(implied);
            }
        }
        return new ArrayList<>(normalized);
    }
}

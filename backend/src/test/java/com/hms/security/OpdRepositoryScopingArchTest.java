package com.hms.security;

import com.hms.repository.OpdRepository;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPD may only be loaded through the tenant-scoped finder.
 *
 * <p>{@link TenantScopingArchTest} already freezes every repository lookup-by-id call site, and it
 * works — all three OPD call sites were in its allowlist, reviewed and believed safe. But its
 * allowlist is keyed on {@code Class#method}, and each of those three methods also loads a patient,
 * a doctor or a hospital by id. Tightening the OPD lookup alone therefore cannot shrink that list,
 * so the generic guard has nothing to say about whether OPD specifically went back to being
 * unscoped.
 *
 * <p>OPD warrants a stricter rule than the generic one anyway. The advice
 * {@code TenantScopingArchTest} gives on failure is "use a *AndHospitalId finder, <b>or</b> compare
 * entity.getHospitalId() to the current hospital". For OPD the second half is impossible: the
 * {@code opd} table has no {@code hospital_id} column and the entity has no such field. Tenancy is
 * only knowable by joining the owning patient, which means the scoped query is not the preferred
 * option — it is the only one.
 *
 * <p>So: no production code may load an OPD by raw id, and the unscoped finder that used to exist
 * may not come back.
 */
class OpdRepositoryScopingArchTest {

    private static final Set<String> LOOKUP_METHODS = Set.of(
            "findById", "getById", "getOne", "getReferenceById");

    @Test
    void noProductionCodeLoadsAnOpdByRawId() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.hms.controller", "com.hms.service");

        Set<String> offenders = new TreeSet<>();
        for (JavaClass c : classes) {
            for (JavaMethod m : c.getMethods()) {
                if (m.getName().contains("lambda$") || m.getName().contains("access$")) {
                    continue;
                }
                for (JavaMethodCall call : m.getMethodCallsFromSelf()) {
                    AccessTarget.MethodCallTarget t = call.getTarget();
                    if (LOOKUP_METHODS.contains(t.getName())
                            && "OpdRepository".equals(t.getOwner().getSimpleName())) {
                        offenders.add(c.getSimpleName() + "#" + m.getName());
                    }
                }
            }
        }

        assertThat(offenders)
                .as("An OPD is being loaded by raw id. Opd has no hospital_id column, so its tenant "
                        + "cannot be checked after the fact — use "
                        + "findByIdAndHospitalIdWithPatientAndDoctor(id, hospitalId), which proves "
                        + "the tenant through the owning patient inside the query.")
                .isEmpty();
    }

    /**
     * The removed unscoped twin. Reintroducing it would give the previous test something to find
     * again; this fails first, and says why.
     */
    @Test
    void theUnscopedOpdFinderStaysDeleted() {
        Set<String> unscopedSingleOpdFinders = Arrays.stream(OpdRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("findById"))
                .filter(m -> !hasHospitalIdParameter(m))
                .map(Method::getName)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        assertThat(unscopedSingleOpdFinders)
                .as("A by-id OPD finder without a hospitalId parameter is back. Opd carries no "
                        + "hospital_id, so such a finder cannot be made safe by its caller.")
                .isEmpty();
    }

    @Test
    void theScopedFinderIsStillThere() {
        boolean present = Arrays.stream(OpdRepository.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("findByIdAndHospitalIdWithPatientAndDoctor")
                        && m.getParameterCount() == 2);

        assertThat(present).as("the only safe way to load one OPD").isTrue();
    }

    private boolean hasHospitalIdParameter(Method m) {
        return Arrays.stream(m.getParameters())
                .anyMatch(p -> {
                    org.springframework.data.repository.query.Param param =
                            p.getAnnotation(org.springframework.data.repository.query.Param.class);
                    return param != null && "hospitalId".equals(param.value());
                });
    }
}

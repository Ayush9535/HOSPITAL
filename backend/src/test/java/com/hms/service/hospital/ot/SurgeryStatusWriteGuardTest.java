package com.hms.service.hospital.ot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 exit criterion: surgeries.status has exactly one writer.
 *
 * The old code set the status inline in five places, each guarded by its own `if`. That
 * is how transitions ended up unaudited and how an illegal move became a matter of which
 * branch you reached. The state machine is now the sole writer; this test stops the old
 * habit returning, because a stray setStatus compiles perfectly well.
 */
class SurgeryStatusWriteGuardTest {

    /** Only these may call setStatus on a Surgery. */
    private static final Set<String> ALLOWED_FILES = Set.of(
            "SurgeryStateMachine.java", // the writer
            "Surgery.java"              // the entity's own accessor + prePersist default
    );

    @Test
    void onlyTheStateMachineWritesSurgeryStatus() throws IOException {
        Path main = Path.of("src", "main", "java", "com", "hms");
        assertThat(Files.isDirectory(main)).as("run from the backend module root").isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (ALLOWED_FILES.contains(name)) continue;

                String source = Files.readString(file, StandardCharsets.UTF_8);
                // Only the OT surgery classes are in scope: other entities have their own status.
                if (!source.contains("com.hms.entity.Surgery") && !source.contains("Surgery s")
                        && !source.contains("Surgery saved") && !source.contains("Surgery surgery")) {
                    continue;
                }
                for (String line : source.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
                    if (trimmed.contains(".setStatus(Surgery.") || trimmed.contains("s.setStatus(")
                            || trimmed.contains("surgery.setStatus(") || trimmed.contains("saved.setStatus(")) {
                        offenders.add(name + ": " + trimmed);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("Surgery status must be written only through SurgeryStateMachine, which validates "
                        + "the transition and writes the audit row.")
                .isEmpty();
    }
}

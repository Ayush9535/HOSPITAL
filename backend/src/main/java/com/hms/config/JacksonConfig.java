package com.hms.config;

import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public Hibernate6Module hibernate6Module() {
        Hibernate6Module module = new Hibernate6Module();
        // Disable FORCE_LAZY_LOADING to prevent loading uninitialized fields
        module.disable(Hibernate6Module.Feature.FORCE_LAZY_LOADING);

        // Hibernate6Module treats JPA's @Transient as "do not serialise" (USE_TRANSIENT_ANNOTATION
        // is ON by default). But in this codebase @Transient never means "private" — it means
        // "computed on read, not a column": Hospital.planName, Appointment.patientName/doctorName,
        // Patient.latestBill. Services fill those in and the UI reads them, so leaving the feature
        // on silently dropped them from every response (the platform tenant list fell back to a
        // hardcoded "FREE" plan for exactly this reason). Serialise them.
        //
        // Safe because nothing sensitive hides behind @Transient here — secrets are excluded with
        // @JsonIgnore or by not exposing the entity at all, which is the mechanism to keep using.
        module.disable(Hibernate6Module.Feature.USE_TRANSIENT_ANNOTATION);

        return module;
    }
}

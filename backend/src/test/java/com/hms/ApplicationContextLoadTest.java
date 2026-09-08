package com.hms;

import com.hms.config.SecurityConfig;
import com.hms.security.JwtAuthenticationFilter;
import com.hms.service.hospital.FormAccessService;
import com.hms.service.pharmacy.PharmacySaleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the entire Spring context.
 *
 * Every other test in this repo is a Mockito unit test or a @WebMvcTest slice, so none of
 * them instantiate the real bean graph. A bean-creation failure, a circular dependency or
 * an ambiguous request mapping therefore reached a running server rather than CI — this
 * repo has already shipped one such break ("break circular bean dependency preventing
 * backend startup"). This test is the guard for that class of failure.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextLoadTest {

    @Autowired
    private ApplicationContext context;

    // Actuator contributes a second RequestMappingHandlerMapping, so qualify the MVC one.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    /**
     * The bean graph spans hospital, pharmacy, nursing and security. Asserting a bean from
     * each wiring-heavy area means a broken constructor injection fails here, by name,
     * instead of surfacing as a generic context failure.
     */
    @Test
    void criticalBeansAreWired() {
        assertThat(context.getBean(SecurityConfig.class)).isNotNull();
        assertThat(context.getBean(JwtAuthenticationFilter.class)).isNotNull();
        assertThat(context.getBean(PharmacySaleService.class)).isNotNull();
        assertThat(context.getBean(FormAccessService.class)).isNotNull();
    }

    /**
     * Two controllers share the class-level base path /hospital/ot. Spring only rejects a
     * genuine duplicate (same verb + same path) at startup, so this pins that invariant:
     * if someone adds a colliding handler, this fails rather than the app refusing to boot.
     */
    @Test
    void noDuplicateRequestMappings() {
        Set<RequestMappingInfo> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();

        for (Map.Entry<RequestMappingInfo, ?> e : handlerMapping.getHandlerMethods().entrySet()) {
            if (!seen.add(e.getKey())) {
                duplicates.add(e.getKey().toString());
            }
        }
        assertThat(duplicates).as("duplicate request mappings").isEmpty();
    }
}

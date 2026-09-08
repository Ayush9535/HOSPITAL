package com.hms.component;

import com.hms.service.platform.PlatformHospitalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PlatformStatsVerifier implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(PlatformStatsVerifier.class);

    @Autowired
    private PlatformHospitalService hospitalService;

    @Override
    public void run(String... args) throws Exception {
        logger.info("=== HOSPITAL STATS VERIFICATION ===");
        try {
            Map<String, Map<String, Long>> stats = hospitalService.getHospitalStats();
            logger.info("RAW STATS FROM SERVICE:");
            stats.forEach((type, counts) -> logger.info("{}: total={}, active={}, inactive={}",
                    type, counts.get("total"), counts.get("active"), counts.get("inactive")));

            logger.info("Checking individual hospitals:");
            hospitalService.getAllHospitals(org.springframework.data.domain.Pageable.unpaged(), null)
                    .forEach(h -> logger.info("Hospital: {} | isActive: {}", h.getName(), h.getIsActive()));

        } catch (Exception e) {
            logger.error("Error verifying stats: {}", e.getMessage(), e);
        }
        logger.info("===================================");
    }
}

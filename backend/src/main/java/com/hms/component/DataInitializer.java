package com.hms.component;

import com.hms.entity.User;
import com.hms.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        try {
            if (!userRepository.existsByEmail("admin123@gmail.com")) {
                User superAdmin = new User();
                superAdmin.setEmail("admin123@gmail.com");
                superAdmin.setPassword(passwordEncoder.encode("pass123"));
                superAdmin.setName("Super Admin");
                superAdmin.setRole("SUPER_ADMIN");
                superAdmin.setHospitalId(null);
                superAdmin.setIsActive(true);
                userRepository.save(superAdmin);
                logger.info("=================================================");
                logger.info("[DataInitializer] Super Admin CREATED");
                logger.info("[DataInitializer]   Email   : admin123@gmail.com");
                logger.info("[DataInitializer]   Password: pass123");
                logger.info("=================================================");
            } else {
                logger.info("[DataInitializer] Super Admin already exists — skipping seed");
            }
        } catch (Exception e) {
            logger.error("[DataInitializer] ERROR seeding Super Admin: {}", e.getMessage(), e);
        }
    }
}

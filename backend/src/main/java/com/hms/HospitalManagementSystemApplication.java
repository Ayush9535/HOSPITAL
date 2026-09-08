package com.hms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HospitalManagementSystemApplication - Main entry point for the HMS
 * application
 * 
 * This is the main Spring Boot application class that bootstraps the entire
 * Hospital Management System. It enables auto-configuration and component
 * scanning.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class HospitalManagementSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(HospitalManagementSystemApplication.class, args);
    }
}

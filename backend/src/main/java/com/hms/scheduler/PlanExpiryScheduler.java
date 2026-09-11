package com.hms.scheduler;

import com.hms.entity.Hospital;
import com.hms.entity.HospitalPlanSubscription;
import com.hms.repository.HospitalPlanSubscriptionRepository;
import com.hms.repository.HospitalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class PlanExpiryScheduler {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private HospitalPlanSubscriptionRepository subscriptionRepository;

    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void checkPlanExpiry() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime warningThreshold = now.plusDays(7);

        List<Hospital> activeHospitals = hospitalRepository.findBySubscriptionStatusIn(
                List.of("ACTIVE", "WARNING"));

        for (Hospital hospital : activeHospitals) {
            subscriptionRepository.findByHospitalIdAndIsCurrentTrue(hospital.getId())
                    .ifPresent(sub -> applyExpiryLogic(hospital, sub, now, warningThreshold));
        }
    }

    private void applyExpiryLogic(Hospital hospital, HospitalPlanSubscription sub,
                                   LocalDateTime now, LocalDateTime warningThreshold) {
        if (sub.getExpiresAt().isBefore(now)) {
            hospital.setIsActive(false);
            hospital.setSubscriptionStatus("EXPIRED");
            hospitalRepository.save(hospital);
        } else if (sub.getExpiresAt().isBefore(warningThreshold)) {
            hospital.setSubscriptionStatus("WARNING");
            hospitalRepository.save(hospital);
        } else if ("WARNING".equals(hospital.getSubscriptionStatus())) {
            hospital.setSubscriptionStatus("ACTIVE");
            hospitalRepository.save(hospital);
        }
    }
}

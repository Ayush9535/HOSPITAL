package com.hms.service.hospital;

import com.hms.entity.*;
import com.hms.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class BillingSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(BillingSchedulerService.class);

    @Autowired
    private IpdAdmissionRepository ipdAdmissionRepository;

    @Autowired
    private WardRepository wardRepository;

    @Autowired
    private BillingRepository billingRepository;

    @Autowired
    private BillingItemRepository billingItemRepository;

    /**
     * Runs at midnight IST (12:00 AM IST) to calculate charges for the day just ended.
     */
    @Scheduled(cron = "0 0 0 * * ?", zone = "Asia/Kolkata")
    @Transactional
    public void processDailyBedCharges() {
        logger.info("Executing daily bed charge processing scheduled task...");
        
        // Find all active IPD admissions (status ADMITTED or DISCHARGE_PLANNED)
        List<IpdAdmission> activeAdmissions = ipdAdmissionRepository.findAll();
        
        String todayStr = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String chargeDescription = "Daily Bed Charge - " + todayStr;

        int processed = 0;
        
        for (IpdAdmission admission : activeAdmissions) {
            // Filter only those not fully discharged
            if ("DISCHARGED".equalsIgnoreCase(admission.getStatus()) || "DISCHARGE".equalsIgnoreCase(admission.getStatus())) {
                continue; 
            }
            
            try {
                processAdmissionCharge(admission, chargeDescription);
                processed++;
            } catch (Exception e) {
                logger.error("Failed to process bed charge for admission ID: {}", admission.getId(), e);
            }
        }
        
        logger.info("Daily bed charge task completed. Processed {} active admissions.", processed);
    }

    private void processAdmissionCharge(IpdAdmission admission, String description) {
        // 1. Fetch existing bill for this IPD
        List<Billing> bills = billingRepository.findByIpdAdmissionId(admission.getId());
        Billing bill = (bills != null && !bills.isEmpty()) ? bills.get(0) : null;
        
        if (bill == null) {
            logger.warn("No primary bill found for IPD admission {}, skipping bed charge.", admission.getId());
            return;
        }

        // 2. Idempotency check: prevent double charging if task runs multiple times.
        List<BillingItem> items = billingItemRepository.findByBillingId(bill.getId());
        boolean alreadyCharged = items.stream().anyMatch(item -> description.equals(item.getDescription()));
        if (alreadyCharged) {
            return; // Already processed for today.
        }

        // 3. Resolve price from Ward.
        BigDecimal bedPrice = BigDecimal.ZERO;
        if (admission.getWardId() != null) {
            Ward ward = wardRepository.findById(admission.getWardId()).orElse(null);
            if (ward != null && ward.getBedPrice() != null) {
                bedPrice = ward.getBedPrice();
            }
        }
        
        if (bedPrice.compareTo(BigDecimal.ZERO) <= 0) {
            logger.info("Bed price is zero for ward linked to admission {}, skipping.", admission.getId());
            return; 
        }

        // 4. Record the new Billing Item.
        BillingItem newItem = new BillingItem();
        newItem.setBillingId(bill.getId());
        newItem.setHospitalId(bill.getHospitalId());
        newItem.setDescription(description);
        newItem.setAmount(bedPrice);
        billingItemRepository.save(newItem);

        // 5. Increment aggregate parent bill total.
        BigDecimal currentTotal = bill.getAmount() != null ? bill.getAmount() : BigDecimal.ZERO;
        bill.setAmount(currentTotal.add(bedPrice));
        billingRepository.save(bill);
    }
}

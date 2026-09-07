package com.hms.controller.hospital;

import com.hms.exception.ResourceNotFoundException;

import com.hms.entity.Hospital;
import com.hms.entity.SupportTicket;
import com.hms.entity.User;
import com.hms.repository.HospitalRepository;
import com.hms.repository.SupportTicketRepository;
import com.hms.repository.UserRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/hospital/tickets", "/clinic/tickets", "/pharmacy/tickets"})
@PreAuthorize("hasRole('HOSPITAL_ADMIN')")
public class HospitalTicketController {

    @Autowired
    private SupportTicketRepository supportTicketRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(HospitalTicketController.class);

    @GetMapping
    public ResponseEntity<?> getMyTickets() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return ResponseEntity.ok(supportTicketRepository.findByHospitalId(hospitalId));
    }

    @PostMapping
    public ResponseEntity<?> createTicket(@RequestBody com.hms.dto.CreateSupportTicketRequest req) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        Long userId = securityHelper.getCurrentUserId();

        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (req.getSubject() == null || req.getSubject().trim().isEmpty() ||
            req.getMessage() == null || req.getMessage().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Subject and message are required.");
        }

        SupportTicket ticket = new SupportTicket();
        ticket.setSubject(req.getSubject().trim());
        ticket.setMessage(req.getMessage().trim());

        // Validate priority and default to MEDIUM if invalid
        String priority = "MEDIUM";
        if (req.getPriority() != null) {
            String reqPriority = req.getPriority().trim().toUpperCase();
            if ("LOW".equals(reqPriority) || "MEDIUM".equals(reqPriority) || "HIGH".equals(reqPriority)) {
                priority = reqPriority;
            }
        }
        ticket.setPriority(priority);

        ticket.setHospitalId(hospitalId);
        ticket.setHospitalName(hospital.getName());
        // Tenant type, so the platform admin sees this ticket under the correct group
        // (Hospital / Clinic / Pharmacy). Without it the ticket saved as NULL and never
        // matched the platform's type-filtered ticket tab.
        ticket.setHospitalType(hospital.getType() != null ? hospital.getType().name() : "HOSPITAL");
        ticket.setAdminName(user.getName());
        ticket.setStatus("OPEN");
        ticket.setCreatedAt(java.time.LocalDateTime.now());

        SupportTicket saved = supportTicketRepository.save(ticket);

        // Notify the platform (Super Admin) in real time so a new ticket appears without a
        // reload. Best-effort: a WebSocket failure must never fail ticket creation.
        try {
            webSocketHandler.broadcastToPlatform(
                    "{\"type\":\"NEW_TICKET\",\"hospitalType\":\"" + saved.getHospitalType() + "\"}");
        } catch (Exception e) {
            log.warn("Failed to broadcast NEW_TICKET to platform: {}", e.getMessage());
        }
        return ResponseEntity.ok(saved);
    }
}

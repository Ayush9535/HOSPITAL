package com.hms.controller.platform;

import com.hms.exception.ResourceNotFoundException;

import com.hms.entity.SupportTicket;
import com.hms.repository.SupportTicketRepository;
import com.hms.service.platform.PlatformTicketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/platform/tickets")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformTicketController {

    @Autowired
    private SupportTicketRepository supportTicketRepository;

    @Autowired
    private PlatformTicketService platformTicketService;

    /**
     * Get all tickets or filter by tenant type (HOSPITAL, CLINIC, PHARMACY).
     */
    @GetMapping
    public ResponseEntity<?> getAllTickets(
            @RequestParam(required = false) String hospitalType,
            @RequestParam(required = false) String status) {
        try {
            // If hospitalType is provided, use isolated service
            if (hospitalType != null && !hospitalType.isEmpty()) {
                if (status != null && !status.isEmpty()) {
                    List<SupportTicket> tickets = platformTicketService.getTicketsByTypeAndStatus(hospitalType, status);
                    return ResponseEntity.ok(tickets);
                }
                List<SupportTicket> tickets = platformTicketService.getTicketsByType(hospitalType);
                return ResponseEntity.ok(tickets);
            }

            // Otherwise, get all tickets (backward compatibility)
            return ResponseEntity.ok(supportTicketRepository.findAll());
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getTicket(
            @PathVariable Long id,
            @RequestParam(required = false) String hospitalType) {
        try {
            if (hospitalType != null && !hospitalType.isEmpty()) {
                SupportTicket ticket = platformTicketService.getTicketById(id, hospitalType);
                return ResponseEntity.ok(ticket);
            }
            SupportTicket ticket = supportTicketRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
            return ResponseEntity.ok(ticket);
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateTicketStatus(
            @PathVariable Long id,
            @RequestParam(required = false) String hospitalType,
            @RequestBody Map<String, String> body) {
        try {
            String newStatus = body.get("status");
            if (newStatus == null || newStatus.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Status is required");
            }

            // If hospitalType is provided, use isolated service
            if (hospitalType != null && !hospitalType.isEmpty()) {
                SupportTicket ticket = platformTicketService.updateTicketStatus(id, hospitalType, newStatus.toUpperCase());
                return ResponseEntity.ok(ticket);
            }

            // Otherwise, update directly (backward compatibility)
            SupportTicket ticket = supportTicketRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

            ticket.setStatus(newStatus.toUpperCase());
            if ("RESOLVED".equalsIgnoreCase(newStatus)) {
                ticket.setResolvedAt(LocalDateTime.now());
            }

            SupportTicket saved = supportTicketRepository.save(ticket);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTicket(
            @PathVariable Long id,
            @RequestParam(required = false) String hospitalType) {
        try {
            if (hospitalType != null && !hospitalType.isEmpty()) {
                platformTicketService.deleteTicket(id, hospitalType);
            } else {
                supportTicketRepository.deleteById(id);
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }
}

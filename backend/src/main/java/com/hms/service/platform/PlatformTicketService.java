package com.hms.service.platform;

import com.hms.exception.ResourceNotFoundException;

import com.hms.entity.SupportTicket;
import com.hms.repository.SupportTicketRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PlatformTicketService {

    @Autowired private com.hms.service.RealtimeNotifier notifier;

    @Autowired
    private SupportTicketRepository repository;

    /**
     * Get all tickets filtered by hospital type (HOSPITAL, CLINIC, or PHARMACY).
     */
    public List<SupportTicket> getTicketsByType(String hospitalType) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }
        return repository.findByHospitalType(hospitalType);
    }

    /**
     * Get tickets filtered by hospital type and status.
     */
    public List<SupportTicket> getTicketsByTypeAndStatus(String hospitalType, String status) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }
        if (status == null || status.isEmpty()) {
            return getTicketsByType(hospitalType);
        }
        return repository.findByHospitalTypeAndStatus(hospitalType, status);
    }

    /**
     * Get ticket count by hospital type.
     */
    public long getTicketCountByType(String hospitalType) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }
        return repository.countByHospitalType(hospitalType);
    }

    /**
     * Get ticket count by hospital type and status.
     */
    public long getTicketCountByTypeAndStatus(String hospitalType, String status) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }
        return repository.countByHospitalTypeAndStatus(hospitalType, status);
    }

    /**
     * Create a new support ticket.
     */
    @Transactional
    public SupportTicket createTicket(String hospitalType, Long hospitalId, String subject, String message, String priority) {
        if (hospitalType == null || hospitalType.isEmpty()) {
            throw new IllegalArgumentException("Hospital type is required");
        }
        if (hospitalId == null) {
            throw new IllegalArgumentException("Hospital ID is required");
        }
        if (subject == null || subject.trim().isEmpty()) {
            throw new IllegalArgumentException("Subject is required");
        }
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Message is required");
        }

        SupportTicket ticket = new SupportTicket();
        ticket.setHospitalType(hospitalType);
        ticket.setHospitalId(hospitalId);
        ticket.setSubject(subject.trim());
        ticket.setMessage(message.trim());
        ticket.setPriority(priority != null ? priority : "MEDIUM");
        ticket.setStatus("OPEN");

        return repository.save(ticket);
    }

    /**
     * Update ticket status (with isolation check).
     */
    @Transactional
    public SupportTicket updateTicketStatus(Long ticketId, String hospitalType, String newStatus) {
        SupportTicket ticket = repository.findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

        // Isolation check
        if (!ticket.getHospitalType().equals(hospitalType)) {
            throw new RuntimeException("Unauthorized: Ticket does not belong to " + hospitalType);
        }

        ticket.setStatus(newStatus);

        if ("RESOLVED".equals(newStatus)) {
            ticket.setResolvedAt(LocalDateTime.now());
        }

        SupportTicket saved = repository.save(ticket);

        // The tenant that raised this ticket is watching its support tab: show the resolution now,
        // not on their next reload. Only that one tenant cares, so this is a targeted push.
        notifier.refresh(saved.getHospitalId());
        return saved;
    }

    /**
     * Get a specific ticket by ID (with isolation check).
     */
    public SupportTicket getTicketById(Long ticketId, String hospitalType) {
        SupportTicket ticket = repository.findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

        // Isolation check
        if (!ticket.getHospitalType().equals(hospitalType)) {
            throw new RuntimeException("Unauthorized: Ticket does not belong to " + hospitalType);
        }

        return ticket;
    }

    /**
     * Delete a ticket (with isolation check).
     */
    @Transactional
    public void deleteTicket(Long ticketId, String hospitalType) {
        SupportTicket ticket = getTicketById(ticketId, hospitalType);
        repository.delete(ticket);
    }
}

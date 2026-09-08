package com.hms.controller.hospital;

import com.hms.security.RequireModule;
import com.hms.service.hospital.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/hospital/notifications")
@RequireModule("NURSING")
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    @GetMapping
    @PreAuthorize("hasRole('NURSE')")
    public ResponseEntity<?> listMy() {
        return ResponseEntity.ok(notificationService.getMyNotifications());
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasRole('NURSE')")
    public ResponseEntity<?> unreadCount() {
        return ResponseEntity.ok(notificationService.getUnreadCount());
    }

    @PutMapping("/{publicId}/read")
    @PreAuthorize("hasRole('NURSE')")
    public ResponseEntity<?> markRead(@PathVariable String publicId) {
        try {
            return ResponseEntity.ok(notificationService.markAsRead(publicId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/read-all")
    @PreAuthorize("hasRole('NURSE')")
    public ResponseEntity<?> markAllRead() {
        notificationService.markAllAsRead();
        return ResponseEntity.ok().build();
    }
}

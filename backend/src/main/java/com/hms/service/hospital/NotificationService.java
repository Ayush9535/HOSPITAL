package com.hms.service.hospital;

import com.hms.entity.Notification;
import com.hms.repository.NotificationRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.security.HospitalWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class NotificationService {
    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private HospitalWebSocketHandler webSocketHandler;

    /**
     * Create a notification. Best-effort and fail-safe: swallows all exceptions.
     */
    public void create(Long recipientUserId, Long hospitalId, String type, String title,
                       String message, String referenceType, Long referenceId) {
        try {
            Notification n = new Notification();
            n.setRecipientUserId(recipientUserId);
            n.setHospitalId(hospitalId);
            n.setType(type);
            n.setTitle(title);
            n.setMessage(message);
            n.setReferenceType(referenceType);
            n.setReferenceId(referenceId);
            n.setIsRead(false);

            notificationRepository.save(n);

            // Broadcast to the websocket to refresh
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.error("Fail-safe notification creation failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Get all notifications for the current logged-in user.
     */
    @Transactional(readOnly = true)
    public List<Notification> getMyNotifications() {
        Long userId = securityHelper.getCurrentUserId();
        return notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Get unread count for the current user.
     */
    @Transactional(readOnly = true)
    public long getUnreadCount() {
        Long userId = securityHelper.getCurrentUserId();
        return notificationRepository.countByRecipientUserIdAndIsReadFalse(userId);
    }

    /**
     * Mark a specific notification as read.
     */
    public Notification markAsRead(String publicId) {
        Notification notification = notificationRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));

        // Tenant isolation
        Long currentHospitalId = securityHelper.getCurrentHospitalId();
        if (!notification.getHospitalId().equals(currentHospitalId)) {
            throw new AccessDeniedException("Access denied to notification");
        }

        // Current user check
        Long currentUserId = securityHelper.getCurrentUserId();
        if (!notification.getRecipientUserId().equals(currentUserId)) {
            throw new AccessDeniedException("Access denied to notification");
        }

        if (!notification.getIsRead()) {
            notification.setIsRead(true);
            notification.setReadAt(LocalDateTime.now());
            notification = notificationRepository.save(notification);
            
            // Broadcast refresh
            webSocketHandler.broadcast(currentHospitalId, "{\"type\":\"REFRESH_DATA\"}");
        }

        return notification;
    }

    /**
     * Mark all notifications for the current user as read.
     */
    public void markAllAsRead() {
        Long userId = securityHelper.getCurrentUserId();
        Long hospitalId = securityHelper.getCurrentHospitalId();
        List<Notification> unread = notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(userId);
        
        boolean updated = false;
        for (Notification n : unread) {
            if (!n.getIsRead() && n.getHospitalId().equals(hospitalId)) {
                n.setIsRead(true);
                n.setReadAt(LocalDateTime.now());
                notificationRepository.save(n);
                updated = true;
            }
        }

        if (updated) {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        }
    }
}

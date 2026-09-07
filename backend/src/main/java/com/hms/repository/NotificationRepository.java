package com.hms.repository;

import com.hms.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    
    List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(Long recipientUserId);
    
    long countByRecipientUserIdAndIsReadFalse(Long recipientUserId);
    
    Optional<Notification> findByPublicId(String publicId);
}

package com.hms.service.hospital;

import com.hms.entity.Notification;
import com.hms.repository.NotificationRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.security.HospitalWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private SecurityContextHelper securityHelper;

    @Mock
    private HospitalWebSocketHandler webSocketHandler;

    @InjectMocks
    private NotificationService service;

    @Test
    void create_savesNotificationAndSwallowsException() {
        // Test successful creation
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(10L, 7L, "ASSIGNMENT", "New Assignment", "Assigned patient", "PATIENT_NURSE_ASSIGNMENT", 100L);

        verify(notificationRepository, times(1)).save(any(Notification.class));
        verify(webSocketHandler, times(1)).broadcast(eq(7L), anyString());

        // Test exception swallowing (fail-safe)
        reset(notificationRepository, webSocketHandler);
        doThrow(new RuntimeException("DB offline")).when(notificationRepository).save(any(Notification.class));

        // Should not throw exception
        service.create(10L, 7L, "ASSIGNMENT", "New Assignment", "Assigned patient", "PATIENT_NURSE_ASSIGNMENT", 100L);
        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    @Test
    void getMyNotifications_filtersByRecipient() {
        when(securityHelper.getCurrentUserId()).thenReturn(10L);
        Notification n = new Notification();
        n.setRecipientUserId(10L);
        when(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(n));

        List<Notification> result = service.getMyNotifications();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRecipientUserId()).isEqualTo(10L);
    }

    @Test
    void getUnreadCount_returnsCount() {
        when(securityHelper.getCurrentUserId()).thenReturn(10L);
        when(notificationRepository.countByRecipientUserIdAndIsReadFalse(10L)).thenReturn(5L);

        long count = service.getUnreadCount();

        assertThat(count).isEqualTo(5L);
    }

    @Test
    void markAsRead_updatesStatusAndEnforcesSecurity() {
        Notification n = new Notification();
        n.setPublicId("ntf-1");
        n.setHospitalId(7L);
        n.setRecipientUserId(10L);
        n.setIsRead(false);

        when(notificationRepository.findByPublicId("ntf-1")).thenReturn(Optional.of(n));
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(10L);
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification updated = service.markAsRead("ntf-1");

        assertThat(updated.getIsRead()).isTrue();
        assertThat(updated.getReadAt()).isNotNull();
        verify(webSocketHandler, times(1)).broadcast(eq(7L), anyString());
    }

    @Test
    void markAsRead_rejectsDifferentHospital() {
        Notification n = new Notification();
        n.setPublicId("ntf-1");
        n.setHospitalId(7L);
        n.setRecipientUserId(10L);

        when(notificationRepository.findByPublicId("ntf-1")).thenReturn(Optional.of(n));
        when(securityHelper.getCurrentHospitalId()).thenReturn(99L); // different hospital

        assertThatThrownBy(() -> service.markAsRead("ntf-1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void markAsRead_rejectsDifferentUser() {
        Notification n = new Notification();
        n.setPublicId("ntf-1");
        n.setHospitalId(7L);
        n.setRecipientUserId(10L);

        when(notificationRepository.findByPublicId("ntf-1")).thenReturn(Optional.of(n));
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(99L); // different user

        assertThatThrownBy(() -> service.markAsRead("ntf-1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void markAllAsRead_updatesAllUserNotifications() {
        Notification n1 = new Notification();
        n1.setHospitalId(7L);
        n1.setRecipientUserId(10L);
        n1.setIsRead(false);

        Notification n2 = new Notification();
        n2.setHospitalId(7L);
        n2.setRecipientUserId(10L);
        n2.setIsRead(false);

        when(securityHelper.getCurrentUserId()).thenReturn(10L);
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(n1, n2));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.markAllAsRead();

        assertThat(n1.getIsRead()).isTrue();
        assertThat(n2.getIsRead()).isTrue();
        verify(webSocketHandler, times(1)).broadcast(eq(7L), anyString());
    }
}

import apiClient from './apiService';

/**
 * notificationService - handles nurse in-app notifications (Phase 1 Nurse module, M8).
 */
const notificationService = {
  /** Get all notifications for the current logged-in nurse. */
  getMyNotifications: async () => {
    const response = await apiClient.get('/hospital/notifications');
    return response.data;
  },

  /** Get unread notification count. */
  getUnreadCount: async () => {
    const response = await apiClient.get('/hospital/notifications/unread-count');
    return response.data;
  },

  /** Mark a notification as read. */
  markAsRead: async (publicId) => {
    const response = await apiClient.put(`/hospital/notifications/${publicId}/read`);
    return response.data;
  },

  /** Mark all notifications as read. */
  markAllAsRead: async () => {
    const response = await apiClient.put('/hospital/notifications/read-all');
    return response.data;
  },
};

export default notificationService;

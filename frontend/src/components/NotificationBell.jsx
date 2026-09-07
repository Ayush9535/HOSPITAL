import React, { useState, useEffect, useRef, useCallback } from 'react';
import notificationService from '../services/notificationService';

/**
 * NotificationBell - Displays notification bell with unread count and a dropdown (Phase 1 Nurse module, M8).
 */
const NotificationBell = ({ refreshKey }) => {
  const [unreadCount, setUnreadCount] = useState(0);
  const [notifications, setNotifications] = useState([]);
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef(null);

  const loadData = useCallback(async () => {
    try {
      const [countData, listData] = await Promise.all([
        notificationService.getUnreadCount(),
        notificationService.getMyNotifications(),
      ]);
      setUnreadCount(typeof countData === 'number' ? countData : countData.count || 0);
      setNotifications(Array.isArray(listData) ? listData : []);
    } catch (err) {
      console.error('Failed to load notifications', err);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData, refreshKey]);

  // Close dropdown on click outside
  useEffect(() => {
    const handleClickOutside = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const toggleDropdown = () => {
    setIsOpen(!isOpen);
  };

  const handleMarkRead = async (publicId) => {
    try {
      await notificationService.markAsRead(publicId);
      loadData();
    } catch (err) {
      console.error('Failed to mark notification read', err);
    }
  };

  const handleMarkAllRead = async () => {
    try {
      await notificationService.markAllAsRead();
      loadData();
    } catch (err) {
      console.error('Failed to mark all read', err);
    }
  };

  const fmtTime = (dt) => {
    if (!dt) return '';
    try {
      const date = new Date(dt);
      const now = new Date();
      const diffMs = now - date;
      const diffMins = Math.floor(diffMs / 60000);

      if (diffMins < 1) return 'Just now';
      if (diffMins < 60) return `${diffMins}m ago`;

      const diffHours = Math.floor(diffMins / 60);
      if (diffHours < 24) return `${diffHours}h ago`;

      return date.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' });
    } catch (e) {
      return '';
    }
  };

  return (
    <div className="relative" ref={containerRef}>
      <button
        onClick={toggleDropdown}
        className="relative p-2 text-gray-500 hover:text-gray-900 hover:bg-gray-100 rounded-lg transition-colors flex items-center justify-center"
        title="Notifications"
      >
        <span className="text-xl">🔔</span>
        {unreadCount > 0 && (
          <span className="absolute top-1 right-1 bg-red-500 text-white font-bold text-xxs w-4 h-4 rounded-full flex items-center justify-center animate-pulse">
            {unreadCount > 9 ? '9+' : unreadCount}
          </span>
        )}
      </button>

      {isOpen && (
        <div className="absolute right-0 mt-2 w-80 bg-white rounded-2xl shadow-xl border border-gray-100 overflow-hidden z-50 animate-fade-in">
          <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between bg-gray-50">
            <span className="font-bold text-sm text-gray-800">Notifications</span>
            {unreadCount > 0 && (
              <button
                onClick={handleMarkAllRead}
                className="text-xs font-semibold text-primary-600 hover:text-primary-800"
              >
                Mark all as read
              </button>
            )}
          </div>

          <div className="max-h-64 overflow-y-auto divide-y divide-gray-50">
            {notifications.length === 0 ? (
              <div className="px-4 py-6 text-center text-xs text-gray-400">No notifications</div>
            ) : (
              notifications.map((n) => (
                <div
                  key={n.publicId || n.id}
                  role="button"
                  tabIndex={0}
                  onClick={() => !n.isRead && handleMarkRead(n.publicId)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      if (!n.isRead) handleMarkRead(n.publicId);
                    }
                  }}
                  className={`px-4 py-3 text-left transition-colors cursor-pointer ${
                    n.isRead ? 'hover:bg-gray-50' : 'bg-blue-50/40 hover:bg-blue-50'
                  }`}
                >
                  <div className="flex items-start gap-2">
                    {!n.isRead && (
                      <span className="mt-1.5 flex h-1.5 w-1.5 shrink-0 rounded-full bg-blue-600 animate-pulse" />
                    )}
                    <div className="flex-1 space-y-0.5">
                      <p
                        className={`text-xs ${n.isRead ? 'font-medium text-gray-700' : 'font-bold text-gray-900'}`}
                      >
                        {n.title}
                      </p>
                      <p className="text-xxs text-gray-500 line-clamp-2">{n.message}</p>
                      <p className="text-[10px] text-gray-400 mt-1 font-medium">
                        {fmtTime(n.createdAt)}
                      </p>
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default NotificationBell;

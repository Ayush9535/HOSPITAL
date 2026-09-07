import { useEffect, useRef } from 'react';
import { API_BASE_URL } from '../services/apiService'; // BUG-028: central base URL
import authService from '../services/authService';

/**
 * useWebSocket - Custom hook for managing multi-tenant real-time WebSocket sync.
 * Handles auto-reconnection with exponential backoff and synchronizes profile settings
 * or triggers silent data reload.
 *
 * @param {Object} user - The current logged in user state
 * @param {Function} setUser - React state setter for the logged in user
 * @param {Function} loadData - Silent data reload trigger function
 */
export default function useWebSocket(user, setUser, loadData) {
  const wsRef = useRef(null);
  const reconnectTimeoutRef = useRef(null);
  const delayRef = useRef(1000); // Start with 1 second delay for reconnection

  // Store latest references to avoid stale closures
  const userRef = useRef(user);
  const setUserRef = useRef(setUser);
  const loadDataRef = useRef(loadData);

  useEffect(() => {
    userRef.current = user;
    setUserRef.current = setUser;
    loadDataRef.current = loadData;
  });

  const connect = () => {
    const currentUser = userRef.current;
    if (!currentUser || !currentUser.hospitalId) return;

    // Cleanup existing connection
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }

    const wsProto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';

    const token = sessionStorage.getItem('token');
    let wsUrl = '';
    if (API_BASE_URL.startsWith('http://') || API_BASE_URL.startsWith('https://')) {
      wsUrl = API_BASE_URL.replace(/^http/, 'ws') + `/ws/hospital/${currentUser.hospitalId}`;
    } else {
      // Relative base URL
      const host = window.location.host;
      wsUrl = `${wsProto}//${host}${API_BASE_URL.startsWith('/') ? '' : '/'}${API_BASE_URL}/ws/hospital/${currentUser.hospitalId}`;
    }

    if (token) {
      wsUrl += `?token=${encodeURIComponent(token)}`;
    }

    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onopen = () => {
      delayRef.current = 1000;
    };

    ws.onmessage = async (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === 'SETTINGS_UPDATED') {
          try {
            const profile = await authService.getProfile();
            const updatedUser = authService.updateCurrentUser(profile);
            if (setUserRef.current && updatedUser) {
              setUserRef.current(updatedUser);
            }
          } catch (err) {
            // profile sync failed silently
          }
        } else if (data.type === 'REFRESH_DATA') {
          if (loadDataRef.current) {
            loadDataRef.current(false);
          }
        } else if (data.type === 'PRESETS_UPDATED') {
          // Preset lists are owned by self-contained components (managers,
          // consultation dropdowns). Fan out via a window event so they can
          // reload without opening their own WebSocket connections.
          globalThis.dispatchEvent(new CustomEvent('hms:presets-updated'));
        }
      } catch (err) {
        // message parse failed silently
      }
    };

    ws.onclose = () => {
      wsRef.current = null;
      scheduleReconnect();
    };

    ws.onerror = () => {
      ws.close();
    };
  };

  const scheduleReconnect = () => {
    if (reconnectTimeoutRef.current) return;

    // Exponential backoff with a cap of 30 seconds
    const delay = delayRef.current;
    delayRef.current = Math.min(delay * 2, 30000);

    reconnectTimeoutRef.current = setTimeout(() => {
      reconnectTimeoutRef.current = null;
      connect();
    }, delay);
  };

  useEffect(() => {
    connect();

    return () => {
      if (wsRef.current) {
        // Remove onclose listener to prevent reconnect when deliberately unmounting
        wsRef.current.onclose = null;
        wsRef.current.close();
        wsRef.current = null;
      }
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
        reconnectTimeoutRef.current = null;
      }
    };
  }, [user?.hospitalId]); // Reconnect if hospitalId changes (e.g. login/logout)
}

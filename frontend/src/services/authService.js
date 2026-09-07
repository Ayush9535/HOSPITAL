import apiClient from './apiService';
import statsCache from './platformStatsCache';

/**
 * Auth Service - Authentication and authorization utilities
 *
 * This service handles:
 * - Super Admin login
 * - Hospital user login
 * - Token storage and retrieval
 * - User information management
 * - Logout
 *
 * @author HMS Team
 * @version Phase-1
 */

const authService = {
  /**
   * Super Admin login
   *
   * @param {string} email - Email address
   * @param {string} password - Password
   * @returns {Promise} Login response with token and user details
   */
  platformLogin: async (email, password) => {
    const response = await apiClient.post('/platform/login', { email, password });

    // Store token and user info in sessionStorage (Tab specific)
    if (response.data.token) {
      sessionStorage.setItem('token', response.data.token);
      sessionStorage.setItem('user', JSON.stringify(response.data));
      try {
        localStorage.setItem('lastPortal', 'PLATFORM');
      } catch (_) {
        /* storage unavailable — fall back to default login */
      }
    }

    return response.data;
  },

  /**
   * Hospital user login (Hospital Admin or Doctor)
   *
   * @param {string} email - Email address
   * @param {string} password - Password
   * @returns {Promise} Login response with token and user details
   */
  hospitalLogin: async (email, password, entityType = 'HOSPITAL') => {
    const response = await apiClient.post('/login', { email, password, entityType });

    // Store token and user info in sessionStorage (Tab specific)
    if (response.data.token) {
      sessionStorage.setItem('token', response.data.token);
      sessionStorage.setItem('user', JSON.stringify(response.data));
      // Remember the portal type in localStorage so that redirects AFTER
      // the session is cleared (logout, expiry, deep links) still land on
      // the correct login page instead of defaulting to the hospital one.
      try {
        localStorage.setItem('lastPortal', response.data.hospitalType || entityType || 'HOSPITAL');
      } catch (_) {
        /* storage unavailable — fall back to default login */
      }
    }

    return response.data;
  },

  /**
   * Returns the login page URL for the current user's portal type.
   * Falls back to the last-used portal (localStorage) when the session is
   * already cleared, so expired clinic/pharmacy sessions still return to
   * their own login page.
   */
  getLoginUrl: () => {
    let hospitalType = null;
    try {
      const userStr = sessionStorage.getItem('user');
      hospitalType = userStr ? JSON.parse(userStr)?.hospitalType : null;
      if (!hospitalType) hospitalType = localStorage.getItem('lastPortal');
    } catch (_) {
      /* fall through to default */
    }
    if (hospitalType === 'CLINIC') return '/login/clinic';
    if (hospitalType === 'PHARMACY') return '/login/pharmacy';
    if (hospitalType === 'PLATFORM') return '/platform/login';
    return '/login/hospital';
  },

  /**
   * Logout user
   * Clears token and user info from sessionStorage
   */
  logout: () => {
    sessionStorage.removeItem('token');
    sessionStorage.removeItem('user');
    // Platform stats are cached in a module-level object that outlives the session.
    // Dropping it here keeps one admin's counts from surfacing for the next.
    statsCache.clear();
  },

  /**
   * Get current user information from sessionStorage
   *
   * @returns {Object|null} User object or null if not logged in
   */
  getCurrentUser: () => {
    const userStr = sessionStorage.getItem('user');
    return userStr ? JSON.parse(userStr) : null;
  },

  /**
   * Check if user is authenticated
   *
   * @returns {boolean} True if user has a valid token
   */
  isAuthenticated: () => {
    return !!sessionStorage.getItem('token');
  },

  /**
   * Check if current user is Super Admin
   *
   * @returns {boolean} True if user is Super Admin
   */
  isSuperAdmin: () => {
    const user = authService.getCurrentUser();
    return user && user.role === 'SUPER_ADMIN';
  },

  /**
   * Check if current user is Hospital Admin
   *
   * @returns {boolean} True if user is Hospital Admin
   */
  isHospitalAdmin: () => {
    const user = authService.getCurrentUser();
    return user && user.role === 'HOSPITAL_ADMIN';
  },

  /**
   * Check if current user is Doctor
   *
   * @returns {boolean} True if user is Doctor
   */
  isDoctor: () => {
    const user = authService.getCurrentUser();
    return (
      user && (user.role === 'DOCTOR' || (user.role === 'HOSPITAL_ADMIN' && user.isSingleDoctor))
    );
  },

  /**
   * Check if current user is Receptionist
   *
   * @returns {boolean} True if user is Receptionist
   */
  isReceptionist: () => {
    const user = authService.getCurrentUser();
    return user && user.role === 'RECEPTIONIST';
  },

  /**
   * Check if current user is Pharmacist
   *
   * @returns {boolean} True if user is Pharmacist
   */
  isPharmacist: () => {
    const user = authService.getCurrentUser();
    return user && user.role === 'PHARMACIST';
  },

  /**
   * Get fresh user profile from server
   */
  getProfile: async () => {
    const response = await apiClient.get('/auth/me');
    return response.data;
  },

  /**
   * Update user profile on the server and update local storage
   *
   * @param {Object} profileData - Profile update fields
   * @returns {Promise} The updated user profile
   */
  updateProfile: async (profileData) => {
    const response = await apiClient.put('/auth/profile', profileData);
    // Sync local storage with updated details
    authService.updateCurrentUser(response.data);
    return response.data;
  },

  /**
   * Update local user storage with fresh data
   */
  updateCurrentUser: (newProfile) => {
    const currentUser = authService.getCurrentUser();
    if (!currentUser) return null;

    // Merge new profile, preserving token if missing in new profile
    const updatedUser = { ...currentUser, ...newProfile };
    if (!newProfile.token) {
      updatedUser.token = currentUser.token;
    }
    sessionStorage.setItem('user', JSON.stringify(updatedUser));
    return updatedUser;
  },
};

export default authService;

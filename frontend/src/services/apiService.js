import axios from 'axios';

/**
 * API Service - Axios wrapper for making HTTP requests
 *
 * This service:
 * - Configures base URL for backend API
 * - Automatically includes JWT token in request headers
 * - Handles authentication errors
 *
 * @author HMS Team
 * @version Phase-1
 */

// Base URL for backend API — exported so components can import instead of redeclaring (BUG-028)
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

// Create axios instance
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 30000, // 30 second request timeout
  maxContentLength: 5242880, // 5 MB response cap
});

// Module-namespace rewrite: shared UI components declare API paths under the
// /hospital/** namespace, but each tenant type owns its own API namespace on the
// backend (/hospital/**, /clinic/**, /pharmacy/**). Rewrite the prefix to match
// the logged-in tenant so a clinic session only ever talks to /clinic/** and a
// standalone pharmacy session to /pharmacy/**.
const MODULE_PREFIX_BY_TYPE = {
  CLINIC: '/clinic/',
  PHARMACY: '/pharmacy/',
};

const rewriteModulePrefix = (url) => {
  if (!url || !url.startsWith('/hospital/')) return url;
  try {
    const userStr = sessionStorage.getItem('user');
    const type = userStr ? JSON.parse(userStr)?.hospitalType : null;
    const prefix = MODULE_PREFIX_BY_TYPE[type];
    return prefix ? url.replace('/hospital/', prefix) : url;
  } catch {
    return url; // malformed session data — leave the URL unchanged
  }
};

// Request interceptor to add JWT token to headers
apiClient.interceptors.request.use(
  (config) => {
    // Route the request to the tenant's module namespace
    config.url = rewriteModulePrefix(config.url);

    // Get token from sessionStorage
    const token = sessionStorage.getItem('token');

    // Add token to Authorization header if it exists
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }

    // Add branch impersonation header if set (multi pharmacy)
    const selectedBranchId = sessionStorage.getItem('selectedBranchId');
    if (selectedBranchId) {
      config.headers['X-Branch-ID'] = selectedBranchId;
    }

    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Map a tenant type to its login page — resilient to missing/invalid session data.
const loginUrlForType = (hospitalType) => {
  if (hospitalType === 'CLINIC') return '/login/clinic';
  if (hospitalType === 'PHARMACY') return '/login/pharmacy';
  if (hospitalType === 'PLATFORM') return '/platform/login';
  return '/login/hospital';
};

// Clear the session and send the user to the correct login page (401 handling).
const clearSessionAndRedirect = () => {
  let hospitalType = null;
  try {
    const userStr = sessionStorage.getItem('user');
    hospitalType = userStr ? JSON.parse(userStr)?.hospitalType : null;
    // Session already gone (expired tab) — use the last-used portal instead
    // of defaulting a clinic/pharmacy user to the hospital login.
    if (!hospitalType) hospitalType = localStorage.getItem('lastPortal');
  } catch {
    hospitalType = null; // fall back to the default hospital login
  }
  sessionStorage.removeItem('token');
  sessionStorage.removeItem('user');
  globalThis.location.href = loginUrlForType(hospitalType);
};

// Helper to handle standard response errors (such as unauthorized session redirect)
const handleResponseError = (error) => {
  // Timeout error — surface a clean message instead of network error
  if (error.code === 'ECONNABORTED') {
    return Promise.reject(new Error('Request timed out. Please try again.'));
  }

  // If 401 Unauthorized (token expired/missing) on a non-login call, redirect to login
  const isLoginEndpoint = error.config?.url?.includes('/login');
  if (error.response?.status === 401 && !isLoginEndpoint) {
    clearSessionAndRedirect();
  }

  return Promise.reject(error);
};

// Response interceptor to handle retries and authentication errors
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = error.config;
    // Only retry safe GET requests on network errors, timeout errors, or 5xx server errors
    if (!config || !config.method || config.method.toLowerCase() !== 'get') {
      return handleResponseError(error);
    }

    config.__retryCount = config.__retryCount || 0;

    if (config.__retryCount >= 3) {
      return handleResponseError(error);
    }

    const shouldRetry =
      !error.response ||
      (error.response.status >= 500 && error.response.status <= 599) ||
      error.code === 'ECONNABORTED';

    if (shouldRetry) {
      config.__retryCount += 1;
      const delay = 1000 * config.__retryCount;
      await new Promise((resolve) => setTimeout(resolve, delay));
      return apiClient(config);
    }

    return handleResponseError(error);
  }
);

export default apiClient;

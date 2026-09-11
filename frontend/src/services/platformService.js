import apiClient from './apiService';

/**
 * Platform Service - API calls for Super Admin operations
 *
 * This service handles API calls for:
 * - Creating hospitals
 * - Listing hospitals
 * - Activating/deactivating hospitals
 * - Viewing hospital details
 *
 * All requests automatically include JWT token via apiClient interceptor.
 * Only Super Admin can access these endpoints.
 *
 * @author HMS Team
 * @version Phase-1
 */

const platformService = {
  /**
   * Get all hospitals
   *
   * @param config extra axios config, e.g. `{ signal }` to make the request cancellable.
   *   Callers already passed this; it was accepted as a 4th argument and silently dropped,
   *   so aborting the controller cancelled nothing and a superseded response could still
   *   land on top of a newer one.
   */
  getHospitals: async (page = 0, size = 10, type = '', config = {}) => {
    const params = { page, size };
    if (type) params.type = type;
    const response = await apiClient.get('/platform/hospitals', { params, ...config });
    return response.data;
  },

  /**
   * Create a new hospital with hospital admin
   */
  createHospital: async (hospitalData) => {
    const response = await apiClient.post('/platform/hospitals', hospitalData);
    return response.data;
  },

  /**
   * Get hospital by ID
   */
  getHospitalById: async (id) => {
    const response = await apiClient.get(`/platform/hospitals/${id}`);
    return response.data;
  },

  /**
   * Update hospital status (activate/deactivate)
   */
  updateHospitalStatus: async (id, isActive, reason) => {
    const response = await apiClient.put(`/platform/hospitals/${id}/status`, { isActive, reason });
    return response.data;
  },

  /**
   * Update hospital details (name, admin email, admin name, isSingleDoctor)
   */
  updateHospitalDetails: async (id, name, adminEmail, adminName, reason, isSingleDoctor) => {
    const response = await apiClient.put(`/platform/hospitals/${id}/details`, {
      name,
      adminEmail,
      adminName,
      reason,
      isSingleDoctor,
    });
    return response.data;
  },

  /**
   * Reset Tenant Admin Password — password chosen by Super Admin
   */
  resetTenantPassword: async (id, password, reason = '') => {
    const response = await apiClient.post(`/platform/hospitals/${id}/reset-password`, {
      password,
      reason,
    });
    return response.data;
  },

  /**
   * Permanently delete a hospital and all its data
   */
  deleteHospital: async (id) => {
    const response = await apiClient.delete(`/platform/hospitals/${id}`);
    return response.data;
  },

  /**
   * Get audit logs
   *
   * @param config extra axios config, e.g. `{ signal }` — see getHospitals.
   */
  getAuditLogs: async (config = {}) => {
    const response = await apiClient.get('/platform/audit-logs', config);
    return response.data;
  },

  /**
   * Get hospital statistics for Super Admin Overview dashboard
   * Returns total, active, and inactive hospital counts
   */
  /**
   * Get user statistics for Super Admin Overview dashboard
   */
  getHospitalStats: async () => {
    const response = await apiClient.get('/platform/hospitals/stats');
    return response.data;
  },

  /**
   * Get all users
   */
  getUsers: async (role = '', hospitalId = '', search = '', page = 0, size = 10) => {
    const response = await apiClient.get(`/platform/users`, {
      params: { role, hospitalId, search, page, size },
    });
    return response.data;
  },

  /**
   * Reset User Password
   */
  resetUserPassword: async (id) => {
    const response = await apiClient.post(`/platform/users/${id}/reset-password`);
    return response.data;
  },

  /**
   * Get all support tickets (submitted by hospital admins)
   */
  getTickets: async (hospitalType = null, status = null) => {
    const params = {};
    if (hospitalType) params.hospitalType = hospitalType;
    if (status) params.status = status;
    const response = await apiClient.get('/platform/tickets', { params });
    return response.data;
  },

  /**
   * Resolve a ticket by ID
   */
  resolveTicket: async (ticketId, hospitalType = null) => {
    const params = {};
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.put(
      `/platform/tickets/${ticketId}/status`,
      { status: 'RESOLVED' },
      { params }
    );
    return response.data;
  },

  /**
   * Get platform FAQs filtered by tenant type (HOSPITAL, CLINIC, PHARMACY)
   */
  getPlatformFaqs: async (hospitalType = null) => {
    const params = {};
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.get('/platform/faqs', { params });
    return response.data;
  },

  /**
   * Add a new FAQ
   */
  addFaq: async (faqData, hospitalType = null) => {
    const params = {};
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.post('/platform/faqs', faqData, { params });
    return response.data;
  },

  /**
   * Update an FAQ by ID
   */
  updateFaq: async (id, faqData, hospitalType = null) => {
    const params = {};
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.put(`/platform/faqs/${id}`, faqData, { params });
    return response.data;
  },

  /**
   * Delete an FAQ by ID
   */
  deleteFaq: async (id, hospitalType = null) => {
    const params = {};
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.delete(`/platform/faqs/${id}`, { params });
    return response.data;
  },

  // ─── Plan Management ─────────────────────────────────────────────────────

  getPlans: async (type = '') => {
    const params = type ? { type } : {};
    const response = await apiClient.get('/platform/plans', { params });
    return response.data;
  },

  getPlanCapabilities: async (type) => {
    const response = await apiClient.get('/platform/plans/capabilities', { params: { type } });
    return response.data;
  },

  createPlan: async (planData) => {
    const response = await apiClient.post('/platform/plans', planData);
    return response.data;
  },

  updatePlan: async (publicId, planData) => {
    const response = await apiClient.put(`/platform/plans/${publicId}`, planData);
    return response.data;
  },

  deletePlan: async (publicId) => {
    const response = await apiClient.delete(`/platform/plans/${publicId}`);
    return response.data;
  },

  assignPlan: async (planPublicId, hospitalPublicId, billingPeriod) => {
    const response = await apiClient.post(`/platform/plans/${planPublicId}/assign`, {
      hospitalPublicId,
      billingPeriod,
    });
    return response.data;
  },

  // ─── Medicine Catalog Management (Global) ──────────────────────────────
  getPlatformMedicines: async (search = '', page = 0, size = 10, hospitalType = null) => {
    const params = { page, size };
    if (search) params.search = search;
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.get('/platform/medicines', { params });
    return response.data;
  },

  createPlatformMedicine: async (data, hospitalType = null) => {
    const params = {};
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.post('/platform/medicines', data, { params });
    return response.data;
  },

  updatePlatformMedicine: async (id, data, hospitalType = null) => {
    const params = {};
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.put(`/platform/medicines/${id}`, data, { params });
    return response.data;
  },

  deletePlatformMedicine: async (id, hospitalType = null) => {
    const params = {};
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.delete(`/platform/medicines/${id}`, { params });
    return response.data;
  },

  importPlatformMedicinesCsv: async (file) => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await apiClient.post('/platform/medicines/import-csv', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return response.data;
  },

  // ─── Global Master Item Catalog Management (Platform Admin) ──────────────
  getMasterItems: async (search = '', page = 0, size = 10, hospitalType = null) => {
    const params = { page, size };
    if (search) params.search = search;
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.get('/platform/inventory-master', { params });
    return response.data;
  },

  createMasterItem: async (data, hospitalType = null) => {
    const params = {};
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.post('/platform/inventory-master', data, { params });
    return response.data;
  },

  updateMasterItem: async (id, data, hospitalType = null) => {
    const params = {};
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.put(`/platform/inventory-master/${id}`, data, { params });
    return response.data;
  },

  deleteMasterItem: async (id, hospitalType = null) => {
    const params = {};
    if (hospitalType) params.hospitalType = hospitalType;
    const response = await apiClient.delete(`/platform/inventory-master/${id}`, { params });
    return response.data;
  },

  importMasterItemsCsv: async (file) => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await apiClient.post('/platform/inventory-master/import-csv', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return response.data;
  },
};

export default platformService;

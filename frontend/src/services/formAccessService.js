import apiClient from './apiService';

/** formAccessService - Files & Access config (Phase 1). */
const formAccessService = {
  list: async () => (await apiClient.get('/hospital/form-access')).data,
  update: async (formKey, payload) =>
    (await apiClient.put(`/hospital/form-access/${formKey}`, payload)).data,
  effective: async () => (await apiClient.get('/hospital/form-access/effective')).data,
};

export default formAccessService;

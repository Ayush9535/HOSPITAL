import apiClient from './apiService';

/** vitalsService - per-hospital OPD vitals config (built-ins + custom vitals). */
const vitalsService = {
  /** Admin: every vital with its effective enabled flag. */
  list: async () => (await apiClient.get('/hospital/vitals')).data,
  /** Any staff role: only the vitals switched ON, for the OPD form / prints. */
  enabled: async () => (await apiClient.get('/hospital/vitals/enabled')).data,
  toggle: async (vitalKey, enabled) =>
    (await apiClient.put(`/hospital/vitals/${vitalKey}`, { enabled })).data,
  addCustom: async (name, unit) =>
    (await apiClient.post('/hospital/vitals/custom', { name, unit })).data,
  deleteCustom: async (publicId) =>
    (await apiClient.delete(`/hospital/vitals/custom/${publicId}`)).data,
};

export default vitalsService;

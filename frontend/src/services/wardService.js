import apiClient from './apiService';

const WardService = {
  getWards: () => apiClient.get('/hospital/wards').then((r) => r.data),
  // Nursing Mgmt: wards with a Nurse Incharge AND an available bed — used only
  // by the IPD admission modal so incharge-less wards are hidden there.
  getWardsForAdmission: () => apiClient.get('/hospital/wards/for-admission').then((r) => r.data),
  createWard: (payload) => apiClient.post('/hospital/wards', payload).then((r) => r.data),
  bulkCreate: (payload) => apiClient.post('/hospital/wards/bulk', payload).then((r) => r.data),
  getBeds: (wardId) => apiClient.get(`/hospital/wards/${wardId}/beds`).then((r) => r.data),
  updateWard: (wardId, payload) =>
    apiClient.put(`/hospital/wards/${wardId}`, payload).then((r) => r.data),
  updateBedStatus: (bedId, remarks = 'Back to available') =>
    apiClient.post(`/hospital/beds/${bedId}/available`, { remarks }).then((r) => r.data),
  getAvailableBeds: (wardId) =>
    apiClient.get('/hospital/beds/available', { params: { ward_id: wardId } }).then((r) => r.data),
  deleteWard: (wardId) => apiClient.delete(`/hospital/wards/${wardId}`).then((r) => r.data),
};

export default WardService;

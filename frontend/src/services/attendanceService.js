import apiClient from './apiService';

/** attendanceService - nurse attendance (Nursing Mgmt Phase D). */
const attendanceService = {
  getSheet: async (wardId, date) =>
    (await apiClient.get(`/hospital/nurse-attendance/sheet?wardId=${wardId}&date=${date}`)).data,
  mark: async (payload) => (await apiClient.post('/hospital/nurse-attendance/mark', payload)).data,
  getSummary: async (wardId, date) =>
    (await apiClient.get(`/hospital/nurse-attendance/summary?wardId=${wardId}&date=${date}`)).data,
  getMine: async (from, to) =>
    (await apiClient.get(`/hospital/nurse-attendance/mine?from=${from}&to=${to}`)).data,
};

export default attendanceService;

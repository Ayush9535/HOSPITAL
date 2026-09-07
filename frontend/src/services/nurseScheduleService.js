import apiClient from './apiService';

/** nurseScheduleService - nurse shift scheduling (Nursing Mgmt Phase B). */
const nurseScheduleService = {
  assign: async (nurseProfileId, date, shiftTemplatePublicId) =>
    (
      await apiClient.post('/hospital/nurse-schedule/assign', {
        nurseProfileId,
        date,
        shiftTemplatePublicId,
      })
    ).data,
  rangeFill: async (payload) =>
    (await apiClient.post('/hospital/nurse-schedule/range-fill', payload)).data,
  remove: async (publicId) => (await apiClient.delete(`/hospital/nurse-schedule/${publicId}`)).data,
  getWard: async (wardId, from, to) =>
    (await apiClient.get(`/hospital/nurse-schedule/ward?wardId=${wardId}&from=${from}&to=${to}`))
      .data,
  getMine: async (from, to) =>
    (await apiClient.get(`/hospital/nurse-schedule/mine?from=${from}&to=${to}`)).data,
};

export default nurseScheduleService;

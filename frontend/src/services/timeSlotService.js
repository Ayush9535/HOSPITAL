import apiClient from './apiService';

/** timeSlotService - Time Slots module (Shift Templates + Appointment Slots). */
const timeSlotService = {
  listShiftTemplates: async (activeOnly = false) =>
    (await apiClient.get(`/hospital/time-slots/shift-templates?activeOnly=${activeOnly}`)).data,
  createShiftTemplate: async (payload) =>
    (await apiClient.post('/hospital/time-slots/shift-templates', payload)).data,
  updateShiftTemplate: async (publicId, payload) =>
    (await apiClient.put(`/hospital/time-slots/shift-templates/${publicId}`, payload)).data,
  deactivateShiftTemplate: async (publicId) =>
    (await apiClient.delete(`/hospital/time-slots/shift-templates/${publicId}`)).data,
  activateShiftTemplate: async (publicId) =>
    (await apiClient.post(`/hospital/time-slots/shift-templates/${publicId}/activate`)).data,

  listAppointmentSlots: async (activeOnly = false) =>
    (await apiClient.get(`/hospital/time-slots/appointment-slots?activeOnly=${activeOnly}`)).data,
  createAppointmentSlot: async (payload) =>
    (await apiClient.post('/hospital/time-slots/appointment-slots', payload)).data,
  updateAppointmentSlot: async (publicId, payload) =>
    (await apiClient.put(`/hospital/time-slots/appointment-slots/${publicId}`, payload)).data,
  deactivateAppointmentSlot: async (publicId) =>
    (await apiClient.delete(`/hospital/time-slots/appointment-slots/${publicId}`)).data,
};

export default timeSlotService;

import apiClient from './apiService';

/** calendarService - Hospital Calendar (Nursing Mgmt Phase G). */
const calendarService = {
  getMonth: async (year, month) =>
    (await apiClient.get(`/hospital/calendar/month?year=${year}&month=${month}`)).data,
  getDay: async (dateStr) => (await apiClient.get(`/hospital/calendar/day?date=${dateStr}`)).data,
  getEvents: async () => (await apiClient.get('/hospital/calendar/events')).data,
  createEvent: async (payload) => (await apiClient.post('/hospital/calendar/events', payload)).data,
  updateEvent: async (publicId, payload) =>
    (await apiClient.put(`/hospital/calendar/events/${publicId}`, payload)).data,
  deleteEvent: async (publicId) =>
    (await apiClient.delete(`/hospital/calendar/events/${publicId}`)).data,
};

export default calendarService;

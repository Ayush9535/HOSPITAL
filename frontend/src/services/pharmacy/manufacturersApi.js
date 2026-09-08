import apiClient from '../apiService';

const manufacturersApi = {
  getAll: async (search = '', page = 0, size = 10) => {
    const query = `?search=${encodeURIComponent(search)}&page=${page}&size=${size}`;
    const response = await apiClient.get(`/pharmacy/manufacturers${query}`);
    return response.data;
  },
  getById: async (id) => {
    const response = await apiClient.get(`/pharmacy/manufacturers/${id}`);
    return response.data;
  },
  create: async (data) => {
    const response = await apiClient.post('/pharmacy/manufacturers', data);
    return response.data;
  },
  update: async (id, data) => {
    const response = await apiClient.put(`/pharmacy/manufacturers/${id}`, data);
    return response.data;
  },
  toggleStatus: async (id) => {
    const response = await apiClient.patch(`/pharmacy/manufacturers/${id}/status`);
    return response.data;
  },
};

export default manufacturersApi;

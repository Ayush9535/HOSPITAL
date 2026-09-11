import apiClient from '../apiService';

const categoriesApi = {
  getAll: async (search = '', page = 0, size = 10) => {
    const query = `?search=${encodeURIComponent(search)}&page=${page}&size=${size}`;
    const response = await apiClient.get(`/pharmacy/categories${query}`);
    return response.data;
  },
  getById: async (id) => {
    const response = await apiClient.get(`/pharmacy/categories/${id}`);
    return response.data;
  },
  create: async (data) => {
    const response = await apiClient.post('/pharmacy/categories', data);
    return response.data;
  },
  update: async (id, data) => {
    const response = await apiClient.put(`/pharmacy/categories/${id}`, data);
    return response.data;
  },
  toggleStatus: async (id) => {
    const response = await apiClient.patch(`/pharmacy/categories/${id}/status`);
    return response.data;
  },
};

export default categoriesApi;

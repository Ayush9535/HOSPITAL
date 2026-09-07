import apiClient from '../apiService';

const suppliersApi = {
  getAll: async (search = '', page = 0, size = 10) => {
    const query = `?search=${encodeURIComponent(search)}&page=${page}&size=${size}`;
    const response = await apiClient.get(`/pharmacy/suppliers${query}`);
    return response.data;
  },
  getById: async (id) => {
    const response = await apiClient.get(`/pharmacy/suppliers/${id}`);
    return response.data;
  },
  create: async (data) => {
    const response = await apiClient.post('/pharmacy/suppliers', data);
    return response.data;
  },
  update: async (id, data) => {
    const response = await apiClient.put(`/pharmacy/suppliers/${id}`, data);
    return response.data;
  },
  delete: async (id) => {
    const response = await apiClient.delete(`/pharmacy/suppliers/${id}`);
    return response.data;
  },
};

export default suppliersApi;
